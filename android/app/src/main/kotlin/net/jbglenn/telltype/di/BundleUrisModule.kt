package net.jbglenn.telltype.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jbglenn.telltype.support.BundleUris
import net.jbglenn.telltype.support.FileProviderBundleUris

/** The debug bundle's content URIs through the app's `FileProvider` (its own module so tests can replace it). */
@Module
@InstallIn(SingletonComponent::class)
internal interface BundleUrisModule {
    @Binds
    fun bindBundleUris(uris: FileProviderBundleUris): BundleUris
}
