package com.android.sharemate.di

import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemRepositoryFirestore
import com.android.sharemate.model.receipt.ReceiptRepository
import com.android.sharemate.model.receipt.ReceiptRepositoryFirestore
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

  @Provides
  @Singleton
  fun provideItemRepository(firestore: FirebaseFirestore): ItemRepository {
    return ItemRepositoryFirestore(firestore)
  }

  @Provides
  @Singleton
  fun provideReceiptRepository(firestore: FirebaseFirestore): ReceiptRepository {
    return ReceiptRepositoryFirestore(firestore)
  }
}
