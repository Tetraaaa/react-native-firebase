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

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.invertase.firebase.common.ReactNativeFirebaseModule;

import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreCommon.rejectPromiseFirestoreException;
import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreSerialize.parseDocumentBatches;
import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreSerialize.parseReadableMap;
import static io.invertase.firebase.firestore.ReactNativeFirebaseFirestoreSerialize.snapshotToWritableMap;
import static io.invertase.firebase.firestore.UniversalFirebaseFirestoreCommon.getDocumentForFirestore;
import static io.invertase.firebase.firestore.UniversalFirebaseFirestoreCommon.getFirestoreForApp;

public class ReactNativeFirebaseFirestoreDocumentModule extends ReactNativeFirebaseModule {
  private static final String SERVICE_NAME = "FirestoreDocument";
  private static Map<String, ListenerRegistration> documentSnapshotListeners = new HashMap<>();

  public ReactNativeFirebaseFirestoreDocumentModule(ReactApplicationContext reactContext) {
    super(reactContext, SERVICE_NAME);
  }

  @ReactMethod
  public void documentGet(String appName, String path, ReadableMap getOptions, Promise promise) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    DocumentReference documentReference = getDocumentForFirestore(firebaseFirestore, path);

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

    Tasks.call(getExecutor(), () -> {
      DocumentSnapshot documentSnapshot = Tasks.await(documentReference.get(source));
      return snapshotToWritableMap(documentSnapshot);
    }).addOnCompleteListener(task -> {
      if (task.isSuccessful()) {
        promise.resolve(task.getResult());
      } else {
        rejectPromiseFirestoreException(promise, task.getException());
      }
    });
  }

  @ReactMethod
  public void documentDelete(String appName, String path, Promise promise) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    DocumentReference documentReference = getDocumentForFirestore(firebaseFirestore, path);
    Tasks.call(getExecutor(), documentReference::delete).addOnCompleteListener(task -> {
      if (task.isSuccessful()) {
        promise.resolve(null);
      } else {
        rejectPromiseFirestoreException(promise, task.getException());
      }
    });
  }

  @ReactMethod
  public void documentSet(String appName, String path, ReadableMap data, ReadableMap options, Promise promise) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    DocumentReference documentReference = getDocumentForFirestore(firebaseFirestore, path);
    Map<String, Object> settableData = parseReadableMap(firebaseFirestore, data);

    Task<Void> setTask;

    if (options.hasKey("merge") && options.getBoolean("merge")) {
      setTask = documentReference.set(settableData, SetOptions.merge());
    } else if (options.hasKey("mergeFields")) {
      List<String> fields = new ArrayList<>();

      for (Object object : Objects.requireNonNull(options.getArray("mergeFields")).toArrayList()) {
        fields.add((String) object);
      }

      setTask = documentReference.set(settableData, SetOptions.mergeFields(fields));
    } else {
      setTask = documentReference.set(settableData);
    }

    // TODO can't wrap in Tasks.call? Never executes
    setTask.addOnCompleteListener(task -> {
      if (task.isSuccessful()) {
        promise.resolve(null);
      } else {
        rejectPromiseFirestoreException(promise, task.getException());
      }
    });
  }

  @ReactMethod
  public void documentUpdate(String appName, String path, ReadableMap data, Promise promise) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    DocumentReference documentReference = getDocumentForFirestore(firebaseFirestore, path);
    Map<String, Object> updateData = parseReadableMap(firebaseFirestore, data);

    documentReference.update(updateData)
      .addOnCompleteListener(task -> {
        if (task.isSuccessful()) {
          promise.resolve(null);
        } else {
          rejectPromiseFirestoreException(promise, task.getException());
        }
      });
  }

  @ReactMethod
  public void documentBatch(String appName, ReadableArray writes, Promise promise) {
    FirebaseFirestore firebaseFirestore = getFirestoreForApp(appName);
    WriteBatch batch = firebaseFirestore.batch();
    List<Object> writesArray = parseDocumentBatches(firebaseFirestore, writes);

    for (Object w : writesArray) {
      Map<String, Object> write = (Map) w;
      String type = (String) write.get("type");
      String path = (String) write.get("path");
      Map<String, Object> data = (Map) write.get("data");

      DocumentReference documentReference = getDocumentForFirestore(firebaseFirestore, path);

      switch (type) {
        case "DELETE":
          batch = batch.delete(documentReference);
          break;
        case "UPDATE":
          batch = batch.update(documentReference, data);
          break;
        case "SET":
          Map<String, Object> options = (Map) write.get("options");

          if (options.containsKey("merge") && (boolean) options.get("merge")) {
            batch = batch.set(documentReference, data, SetOptions.merge());
          } else if (options.containsKey("mergeFields")) {
            List<String> fields = new ArrayList<>();

            for (Object object : Objects.requireNonNull((List) options.get("mergeFields"))) {
              fields.add((String) object);
            }

            batch = batch.set(documentReference, data, SetOptions.mergeFields(fields));
          } else {
            batch = batch.set(documentReference, data);
          }
          break;
      }

      batch.commit().addOnCompleteListener(task -> {
        if (task.isSuccessful()) {
          promise.resolve(null);
        } else {
          rejectPromiseFirestoreException(promise, task.getException());
        }
      });
    }
  }
}
