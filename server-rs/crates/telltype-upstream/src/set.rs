//! The configured upstreams: routing by model name, health probes and model lists.

use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use serde_json::Value;
use tokio::sync::Semaphore;

use crate::error::UpstreamError;

mod listing;

/// One upstream as configured.
#[derive(Debug, Clone)]
pub struct UpstreamSpec {
    /// `cleanup` or `commands`; used in logs and `/healthz`.
    pub role: String,
    /// Base URL without a trailing slash, e.g. `http://127.0.0.1:8172`.
    pub url: String,
    /// The real model name: `/healthz` reports it, and `/v1/models` lists it in place of an upstream
    /// entry whose id is one of [`Self::aliases`].
    pub model: String,
    /// Deprecated request model name -> upstream model name. Requests that name an alias still
    /// route here; alias names are never listed in `/v1/models`.
    pub aliases: BTreeMap<String, String>,
    pub timeout: Duration,
    pub max_in_flight: usize,
    pub health_path: String,
    /// Further model names clients may use (`*`/`?` globs); empty: `model` and the aliases only.
    pub allow: Vec<String>,
}

impl UpstreamSpec {
    /// Whether clients may name `model` here: the configured model, an alias or its target, or a
    /// name matching one of [`Self::allow`].
    #[must_use]
    pub fn allows(&self, model: &str) -> bool {
        model == self.model
            || self.aliases.contains_key(model)
            || self.aliases.values().any(|target| target == model)
            || self.allow.iter().any(|pattern| glob(pattern, model))
    }
}

/// `*` (any run of characters) and `?` (one character) matching, case-sensitive.
#[must_use]
pub fn glob(pattern: &str, text: &str) -> bool {
    let (pattern, text): (Vec<char>, Vec<char>) = (pattern.chars().collect(), text.chars().collect());
    let (mut p, mut t) = (0, 0);
    let mut star: Option<(usize, usize)> = None;
    while t < text.len() {
        match pattern.get(p) {
            Some('*') => {
                star = Some((p, t));
                p += 1;
            }
            Some(c) if *c == '?' || *c == text[t] => {
                p += 1;
                t += 1;
            }
            _ => match star {
                Some((sp, st)) => {
                    p = sp + 1;
                    t = st + 1;
                    star = Some((sp, st + 1));
                }
                None => return false,
            },
        }
    }
    pattern[p..].iter().all(|c| *c == '*')
}

pub(crate) struct Upstream {
    pub(crate) spec: UpstreamSpec,
    pub(crate) slots: Arc<Semaphore>,
    /// Model ids from the upstream's last successful `/v1/models`, after [`Upstream::rename`].
    listed: RwLock<Vec<String>>,
    /// The upstream's own id that is listed as `spec.model` (an upstream that still serves its
    /// model under an alias name, e.g. llama-server started with `--alias cleanup`).
    renamed_from: RwLock<Option<String>>,
}

impl Upstream {
    /// The id the upstream itself knows for `name`: its alias id when `name` is the real model name
    /// listed in its place, else `name`.
    fn upstream_id(&self, name: &str) -> String {
        if name == self.spec.model
            && let Some(original) = self.renamed_from.read().ok().and_then(|from| from.clone())
        {
            return original;
        }
        name.to_owned()
    }

    /// An entry whose id is one of this upstream's alias names is listed under the real model
    /// name instead (its `aliases` field dropped), so alias names never reach a client.
    fn rename(&self, mut entry: Value) -> (Value, Option<String>) {
        let id = entry.get("id").and_then(Value::as_str).map(str::to_owned);
        match id {
            Some(id) if id != self.spec.model && self.spec.aliases.contains_key(&id) => {
                if let Some(object) = entry.as_object_mut() {
                    object.insert("id".to_owned(), Value::String(self.spec.model.clone()));
                    object.remove("aliases");
                }
                (entry, Some(id))
            }
            _ => (entry, None),
        }
    }
}

/// Result of a health probe of one upstream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Probe {
    pub role: String,
    pub model: String,
    pub ready: bool,
}

/// The merged `/v1/models` of all upstreams.
#[derive(Debug, Clone, PartialEq)]
pub struct ModelList {
    /// `OpenAI` model objects: every upstream's entries (first id wins), each with `telltype.roles`;
    /// alias names are not listed, and a model file another upstream already serves is listed once.
    pub data: Vec<Value>,
    /// Per role, the ids that upstream offers (a duplicate file under the id that is listed), or
    /// `None` when it could not be asked.
    pub per_role: Vec<(String, Option<Vec<String>>)>,
}

/// Where a chat request goes.
pub(crate) struct Route {
    pub(crate) upstream: usize,
    /// `Some(name)`: rewrite `model` to this before forwarding.
    pub(crate) rewrite: Option<String>,
}

pub struct Upstreams {
    pub(crate) list: Vec<Upstream>,
    pub(crate) client: reqwest::Client,
    pub(crate) health_timeout: Duration,
    /// A listed-twice id (the same model file under another upstream) -> the id that is listed.
    duplicates: RwLock<BTreeMap<String, String>>,
}

impl Upstreams {
    pub fn new(specs: Vec<UpstreamSpec>, health_timeout: Duration) -> Result<Self, UpstreamError> {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .pool_idle_timeout(Duration::from_secs(30))
            .build()
            .map_err(|e| UpstreamError::Client(e.to_string()))?;
        let list = specs
            .into_iter()
            .map(|spec| Upstream {
                slots: Arc::new(Semaphore::new(spec.max_in_flight)),
                listed: RwLock::new(Vec::new()),
                renamed_from: RwLock::new(None),
                spec,
            })
            .collect();
        Ok(Self {
            list,
            client,
            health_timeout,
            duplicates: RwLock::new(BTreeMap::new()),
        })
    }

    #[must_use]
    pub fn roles(&self) -> Vec<String> {
        self.list.iter().map(|u| u.spec.role.clone()).collect()
    }

    /// Probe every upstream's health route at once; 200 within the health timeout means ready.
    pub async fn probe_all(&self) -> Vec<Probe> {
        let probes = self.list.iter().map(|u| async move {
            let ready = self
                .get(&u.spec.url, &u.spec.health_path)
                .await
                .is_some_and(|(status, _)| status == 200);
            Probe {
                role: u.spec.role.clone(),
                model: u.spec.model.clone(),
                ready,
            }
        });
        futures_util::future::join_all(probes).await
    }

    /// Ask every upstream for its models and merge the lists. Also refreshes the routing cache.
    pub async fn models(&self) -> ModelList {
        let fetched = futures_util::future::join_all(self.list.iter().map(|u| self.fetch_models(u))).await;
        let listed = self
            .list
            .iter()
            .zip(fetched)
            .map(|(upstream, entries)| listing::Listed {
                role: upstream.spec.role.clone(),
                entries,
            })
            .collect();
        let merged = listing::merge(listed);
        if let Ok(mut duplicates) = self.duplicates.write() {
            *duplicates = merged.duplicates;
        }
        ModelList {
            data: merged.data,
            per_role: merged.per_role,
        }
    }

    /// The upstream's entries, each with the model file it runs when that can be found: from a
    /// router preset's arguments, or for an upstream with one model from llama-server's `/props`.
    async fn fetch_models(&self, upstream: &Upstream) -> Option<Vec<(Value, Option<String>)>> {
        let (status, body) = self.get(&upstream.spec.url, "/v1/models").await?;
        if status != 200 {
            return None;
        }
        let parsed: Value = serde_json::from_slice(&body).ok()?;
        let mut renamed_from = None;
        let entries: Vec<Value> = parsed
            .get("data")?
            .as_array()?
            .iter()
            .map(|entry| {
                let (entry, original) = upstream.rename(entry.clone());
                renamed_from = renamed_from.take().or(original);
                entry
            })
            // Only allowed models are listed, routed to and shown in /healthz.
            .filter(|entry| {
                entry
                    .get("id")
                    .and_then(Value::as_str)
                    .is_some_and(|id| upstream.spec.allows(id))
            })
            .collect();
        if let Ok(mut slot) = upstream.renamed_from.write() {
            *slot = renamed_from;
        }
        let ids = entries
            .iter()
            .filter_map(|e| e.get("id").and_then(Value::as_str).map(str::to_owned))
            .collect();
        if let Ok(mut listed) = upstream.listed.write() {
            *listed = ids;
        }
        let mut files: Vec<Option<String>> = entries.iter().map(listing::file_from_args).collect();
        if let [only] = files.as_mut_slice()
            && only.is_none()
        {
            *only = self.served_file(&upstream.spec.url).await;
        }
        Some(entries.into_iter().zip(files).collect())
    }

    /// The model file a single-model llama-server runs (`model_path` of its `/props`).
    async fn served_file(&self, base: &str) -> Option<String> {
        let (status, body) = self.get(base, "/props").await?;
        if status != 200 {
            return None;
        }
        let props: Value = serde_json::from_slice(&body).ok()?;
        props
            .get("model_path")
            .and_then(Value::as_str)
            .map(listing::normalise)
    }

    async fn get(&self, base: &str, path: &str) -> Option<(u16, bytes::Bytes)> {
        let request = self
            .client
            .get(format!("{base}{path}"))
            .timeout(self.health_timeout)
            .send();
        let response = request.await.ok()?;
        let status = response.status().as_u16();
        let body = tokio::time::timeout(self.health_timeout, response.bytes())
            .await
            .ok()?
            .ok()?;
        Some((status, body))
    }

    /// Find the upstream for a request's model: aliases first (deprecated names), then listed
    /// models (asking the upstreams again when the name is not known yet). A listed name that
    /// stands for the upstream's alias id is rewritten to that id.
    pub(crate) async fn route(&self, model: &str) -> Result<Route, UpstreamError> {
        if let Some(route) = self.route_known(model) {
            return Ok(route);
        }
        let list = self.models().await;
        if let Some(route) = self.route_known(model) {
            return Ok(route);
        }
        if let Some((role, _)) = list.per_role.iter().find(|(_, ids)| ids.is_none()) {
            return Err(UpstreamError::NotReady(format!(
                "model '{model}' is not known and {role} cannot be asked for its models"
            )));
        }
        Err(UpstreamError::ModelNotFound(format!(
            "model '{model}' is not available (GET /v1/models lists them)"
        )))
    }

    fn route_known(&self, model: &str) -> Option<Route> {
        let kept = self
            .duplicates
            .read()
            .ok()
            .and_then(|duplicates| duplicates.get(model).cloned());
        if let Some(kept) = kept {
            // The same file under the name that is listed: send it there, renamed.
            let mut route = self.route_listed(&kept)?;
            route.rewrite = Some(route.rewrite.unwrap_or(kept));
            return Some(route);
        }
        for (index, upstream) in self.list.iter().enumerate() {
            if let Some(target) = upstream.spec.aliases.get(model) {
                let target = upstream.upstream_id(target);
                let rewrite = (target != model).then_some(target);
                return Some(Route {
                    upstream: index,
                    rewrite,
                });
            }
        }
        self.route_listed(model)
    }

    fn route_listed(&self, model: &str) -> Option<Route> {
        let upstream = self
            .list
            .iter()
            .position(|u| u.listed.read().is_ok_and(|ids| ids.iter().any(|id| id == model)))?;
        let target = self.list[upstream].upstream_id(model);
        let rewrite = (target != model).then_some(target);
        Some(Route { upstream, rewrite })
    }
}

#[cfg(test)]
mod tests;
