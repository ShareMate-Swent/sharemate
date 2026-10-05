package com.android.sharemate.di

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

  @Provides
  @Singleton
  fun provideFirebaseFirestore(): FirebaseFirestore {
    val firestore = FirebaseFirestore.getInstance()

    // Configuration explicite du cache hors ligne
    val settings =
        FirebaseFirestoreSettings.Builder()
            // Active la persistance locale (activée par défaut sur Android, mais bien de le forcer)
            .setPersistenceEnabled(true)
            // Fixe la taille limite du cache à 100 MB. Si dépassée, Firestore supprime les vieux
            // documents.
            .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .build()

    firestore.firestoreSettings = settings
    return firestore
  }
}
