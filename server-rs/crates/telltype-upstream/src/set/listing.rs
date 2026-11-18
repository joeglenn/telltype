//! Merging the upstreams' model lists into one `/v1/models`: every entry says which roles it
//! serves, and one model file is listed once.

use std::collections::BTreeMap;

use serde_json::{Value, json};

/// One upstream's answer to `/v1/models`, ready to merge.
pub(crate) struct Listed {
    /// The upstream's role (`cleanup`, `commands`).
    pub(crate) role: String,
    /// Its entries with the model file each one runs, when known; `None` when it could not be asked.
    pub(crate) entries: Option<Vec<(Value, Option<String>)>>,
}

/// The merged list.
pub(crate) struct Merged {
    /// `OpenAI` model objects, each with `telltype.roles`.
    pub(crate) data: Vec<Value>,
    /// Per role, the ids it offers after merging (a duplicate replaced by the id kept for its file).
    pub(crate) per_role: Vec<(String, Option<Vec<String>>)>,
    /// A dropped id -> the id listed for the same model file.
    pub(crate) duplicates: BTreeMap<String, String>,
}

/// Merge in upstream order. The first entry with an id wins; an entry running a model file that an
/// earlier entry already runs is dropped, and its role is added to that entry instead (the cleanup
/// upstream comes first, so its configured name is the one listed).
pub(crate) fn merge(listed: Vec<Listed>) -> Merged {
    let mut data: Vec<Value> = Vec::new();
    let mut by_file: BTreeMap<String, (usize, String)> = BTreeMap::new();
    let mut duplicates = BTreeMap::new();
    let mut per_role = Vec::new();
    for upstream in listed {
        let Some(entries) = upstream.entries else {
            per_role.push((upstream.role, None));
            continue;
        };
        let mut ids: Vec<String> = Vec::new();
        for (mut entry, file) in entries {
            let Some(id) = entry.get("id").and_then(Value::as_str).map(str::to_owned) else {
                continue;
            };
            // Two presets of one upstream may run the same file with different settings on purpose;
            // only a file that another upstream already serves is a duplicate.
            let same_file_elsewhere = file
                .as_ref()
                .and_then(|f| by_file.get(f))
                .filter(|(_, role)| *role != upstream.role)
                .map(|(index, _)| *index);
            let existing = data
                .iter()
                .position(|d| d.get("id").and_then(Value::as_str) == Some(id.as_str()))
                .or(same_file_elsewhere);
            let index = if let Some(index) = existing {
                index
            } else {
                set_roles(&mut entry, &[]);
                data.push(entry);
                data.len() - 1
            };
            if let Some(file) = file {
                by_file.entry(file).or_insert((index, upstream.role.clone()));
            }
            add_role(&mut data[index], &upstream.role);
            let kept = id_of(&data[index]);
            if kept != id {
                duplicates.insert(id, kept.clone());
            }
            if !ids.contains(&kept) {
                ids.push(kept);
            }
        }
        per_role.push((upstream.role, Some(ids)));
    }
    Merged {
        data,
        per_role,
        duplicates,
    }
}

/// The model file a llama-server entry runs: the value after `--model` (or `-m`) in the
/// `status.args` that a router-mode llama-server reports for each preset.
pub(crate) fn file_from_args(entry: &Value) -> Option<String> {
    let args = entry.get("status")?.get("args")?.as_array()?;
    let mut args = args.iter().filter_map(Value::as_str);
    while let Some(arg) = args.next() {
        if arg == "--model" || arg == "-m" {
            return args.next().map(normalise);
        }
    }
    None
}

/// One spelling per file: the canonical path when it exists here, else the path as given.
pub(crate) fn normalise(path: &str) -> String {
    std::fs::canonicalize(path).map_or_else(|_| path.to_owned(), |p| p.display().to_string())
}

fn id_of(entry: &Value) -> String {
    entry
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

fn set_roles(entry: &mut Value, roles: &[String]) {
    if let Some(object) = entry.as_object_mut() {
        object.insert("telltype".to_owned(), json!({ "roles": roles }));
    }
}

fn add_role(entry: &mut Value, role: &str) {
    let mut roles: Vec<String> = entry
        .pointer("/telltype/roles")
        .and_then(Value::as_array)
        .map(|r| r.iter().filter_map(Value::as_str).map(str::to_owned).collect())
        .unwrap_or_default();
    if !roles.iter().any(|r| r == role) {
        roles.push(role.to_owned());
    }
    set_roles(entry, &roles);
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::{Listed, file_from_args, merge};

    fn entry(id: &str) -> serde_json::Value {
        json!({"id": id, "object": "model"})
    }

    #[test]
    fn a_file_served_twice_is_listed_once_under_the_first_name_with_both_roles() {
        let merged = merge(vec![
            Listed {
                role: "cleanup".to_owned(),
                entries: Some(vec![(entry("gemma-e4b"), Some("/m/e4b.gguf".to_owned()))]),
            },
            Listed {
                role: "commands".to_owned(),
                entries: Some(vec![
                    (entry("qwen-4b"), Some("/m/q4.gguf".to_owned())),
                    (entry("gemma-e4b_q4_0-it"), Some("/m/e4b.gguf".to_owned())),
                    (entry("no-file"), None),
                    (entry("qwen-4b-other-settings"), Some("/m/q4.gguf".to_owned())),
                ]),
            },
        ]);
        let ids: Vec<_> = merged.data.iter().map(|d| d["id"].as_str().unwrap()).collect();
        assert_eq!(
            ids,
            ["gemma-e4b", "qwen-4b", "no-file", "qwen-4b-other-settings"],
            "two presets of one upstream on one file both stay"
        );
        assert_eq!(
            merged.data[0]["telltype"]["roles"],
            json!(["cleanup", "commands"])
        );
        assert_eq!(merged.data[1]["telltype"]["roles"], json!(["commands"]));
        assert_eq!(
            merged.duplicates.get("gemma-e4b_q4_0-it").map(String::as_str),
            Some("gemma-e4b")
        );
        assert_eq!(
            merged.per_role[1].1.as_deref(),
            Some(
                &[
                    "qwen-4b".to_owned(),
                    "gemma-e4b".to_owned(),
                    "no-file".to_owned(),
                    "qwen-4b-other-settings".to_owned()
                ][..]
            )
        );
    }

    #[test]
    fn the_same_id_from_two_upstreams_keeps_the_first_entry_and_gains_the_role() {
        let merged = merge(vec![
            Listed {
                role: "cleanup".to_owned(),
                entries: Some(vec![(entry("a"), None)]),
            },
            Listed {
                role: "commands".to_owned(),
                entries: None,
            },
            Listed {
                role: "extra".to_owned(),
                entries: Some(vec![(entry("a"), None)]),
            },
        ]);
        assert_eq!(merged.data.len(), 1);
        assert_eq!(merged.data[0]["telltype"]["roles"], json!(["cleanup", "extra"]));
        assert_eq!(merged.per_role[1], ("commands".to_owned(), None));
        assert!(merged.duplicates.is_empty());
    }

    #[test]
    fn the_file_comes_from_the_model_argument_of_a_router_preset() {
        let preset = json!({"id": "x", "status": {"args": ["llama-server", "--port", "0", "--model", "/no/such/x.gguf"]}});
        assert_eq!(file_from_args(&preset).as_deref(), Some("/no/such/x.gguf"));
        let short = json!({"id": "x", "status": {"args": ["-m", "/no/such/y.gguf"]}});
        assert_eq!(file_from_args(&short).as_deref(), Some("/no/such/y.gguf"));
        assert_eq!(file_from_args(&json!({"id": "x"})), None);
        assert_eq!(
            file_from_args(&json!({"id": "x", "status": {"args": ["--model"]}})),
            None
        );
    }
}
