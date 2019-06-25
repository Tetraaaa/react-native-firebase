package io.invertase.firebase.firestore;

/*
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this library except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;

import io.invertase.firebase.common.ReactNativeFirebaseEvent;
import io.invertase.firebase.common.ReactNativeFirebaseEventEmitter;
import io.invertase.firebase.common.ReactNativeFirebaseModule;

import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreCommon.rejectPromiseFirestoreException;
import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreSerialize.snapshotToWritableMap;
import static io.invertase.firebase.firestore.UniversalFirebaseFirestoreCommon.getFirestoreForApp;
import static io.invertase.firebase.firestore.UniversalFirebaseFirestoreCommon.getQueryForFirestore;

public class ReactNativeFirebaseFirestoreCollectionModule extends ReactNativeFirebaseModule {
  private static final String SERVICE_NAME = "FirestoreCollection";
  private static Map<String, ListenerRegistration> collectionSnapshotListeners = new HashMap<>();

  public ReactNativeFirebaseFirestoreCollectionModule(ReactApplicationContext reactContext) {
    super(reactContext, SERVICE_NAME);
  }

  @Override
  public void onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy();

    Iterator refIterator = collectionSnapshotListeners.entrySet().iterator();
    while (refIterator.hasNext()) {
      Map.Entry pair = (Map.Entry) refIterator.next();
      ListenerRegistration listenerRegistration = (ListenerRegistration) pair.getValue();
      listenerRegistration.remove();
      refIterator.remove(); // avoids a ConcurrentModificationException
    }
  }

  @ReactMethod
  public void collectionOnSnapshot(
    String appName,
    String path,
    String type,
    ReadableArray filters,
    ReadableArray orders,
    ReadableMap options,
    String listenerId,
    ReadableMap listenerOptions
  ) {
    if (collectionSnapshotListeners.containsKey(listenerId)) {
      return;
    }

    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    ReactNativeFirebaseFirestoreQuery firestoreQuery = new ReactNativeFirebaseFirestoreQuery(
      firebaseFirestore,
      getQueryForFirestore(firebaseFirestore, path, type),
      filters,
      orders,
      options
    );

    final EventListener<QuerySnapshot> listener = (querySnapshot, exception) -> {
      if (exception != null) {
        ListenerRegistration listenerRegistration = collectionSnapshotListeners.remove(listenerId);
        if (listenerRegistration != null) {
          listenerRegistration.remove();
        }
        sendOnSnapshotError(appName, listenerId, exception);
      } else {
        sendOnSnapshotEvent(appName, listenerId, querySnapshot);
      }
    };

    MetadataChanges metadataChanges;

    if (listenerOptions != null && listenerOptions.hasKey("includeMetadataChanges")
      && listenerOptions.getBoolean("includeMetadataChanges")) {
      metadataChanges = MetadataChanges.INCLUDE;
    } else {
      metadataChanges = MetadataChanges.EXCLUDE;
    }

    ListenerRegistration listenerRegistration = firestoreQuery.query.addSnapshotListener(
      metadataChanges,
      listener
    );

    collectionSnapshotListeners.put(listenerId, listenerRegistration);
  }

  @ReactMethod
  public void collectionOffSnapshot(
    String appName,
    String listenerId
  ) {
    ListenerRegistration listenerRegistration = collectionSnapshotListeners.remove(listenerId);
    if (listenerRegistration != null) {
      listenerRegistration.remove();
    }
  }

  @ReactMethod
  public void collectionGet(
    String appName,
    String path,
    String type,
    ReadableArray filters,
    ReadableArray orders,
    ReadableMap options,
    ReadableMap getOptions,
    Promise promise
  ) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    ReactNativeFirebaseFirestoreQuery query = new ReactNativeFirebaseFirestoreQuery(
      firebaseFirestore,
      getQueryForFirestore(firebaseFirestore, path, type),
      filters,
      orders,
      options
    );

    Source source;

    if (getOptions != null && getOptions.hasKey("source")) {
      String optionsSource = getOptions.getString("source");
      if ("server".equals(optionsSource)) {
        source = Source.SERVER;
      } else if ("cache".equals(optionsSource)) {
        source = Source.CACHE;
      } else {
        source = Source.DEFAULT;
      }
    } else {
      source = Source.DEFAULT;
    }

    query.get(getExecutor(), source)
      .addOnCompleteListener(task -> {
        if (task.isSuccessful()) {
          promise.resolve(task.getResult());
        } else {
          rejectPromiseFirestoreException(promise, task.getException());
        }
      });
  }

  private void sendOnSnapshotEvent(String appName, String listenerId, QuerySnapshot querySnapshot) {
    Tasks.call(getExecutor(), () -> snapshotToWritableMap(querySnapshot)).addOnCompleteListener(task -> {
      if (task.isSuccessful()) {
        WritableMap body = Arguments.createMap();
        body.putMap("snapshot", task.getResult());

        ReactNativeFirebaseEventEmitter emitter = ReactNativeFirebaseEventEmitter.getSharedInstance();

        emitter.sendEvent(new ReactNativeFirebaseFirestoreEvent(
          ReactNativeFirebaseFirestoreEvent.COLLECTION_EVENT_SYNC,
          body,
          appName,
          listenerId
        ));
      } else {
        sendOnSnapshotError(appName, listenerId, task.getException());
      }
    });
  }

  private void sendOnSnapshotError(String appName, String listenerId, Exception exception) {
    WritableMap body = Arguments.createMap();
    WritableMap error = Arguments.createMap();

    if (exception instanceof FirebaseFirestoreException) {
      UniversalFirebaseFirestoreException firestoreException = new UniversalFirebaseFirestoreException((FirebaseFirestoreException) exception, exception.getCause());
      error.putString("code", firestoreException.getCode());
      error.putString("message", firestoreException.getMessage());
    } else {
      error.putString("code", "unknown");
      error.putString("message", "An unknown error occurred");
    }

    body.putMap("error", error);
    ReactNativeFirebaseEventEmitter emitter = ReactNativeFirebaseEventEmitter.getSharedInstance();

    emitter.sendEvent(new ReactNativeFirebaseFirestoreEvent(
      ReactNativeFirebaseFirestoreEvent.COLLECTION_EVENT_SYNC,
      body,
      appName,
      listenerId
    ));
  }
}
