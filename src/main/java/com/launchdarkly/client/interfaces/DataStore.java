package com.launchdarkly.client.interfaces;

import java.io.Closeable;
import java.util.Map;

/**
 * A thread-safe, versioned store for feature flags and related objects received from the
 * streaming API.  Implementations should permit concurrent access and updates.
 * <p>
 * Delete and upsert requests are versioned-- if the version number in the request is less than
 * the currently stored version of the object, the request should be ignored.
 * <p>
 * These semantics support the primary use case for the store, which synchronizes a collection
 * of objects based on update messages that may be received out-of-order.
 * @since 5.0.0
 */
public interface DataStore extends Closeable {
  /**
   * Returns the object to which the specified key is mapped, or
   * null if the key is not associated or the associated object has
   * been deleted.
   *
   * @param <T> class of the object that will be returned
   * @param kind the kind of object to get
   * @param key the key whose associated object is to be returned
   * @return the object to which the specified key is mapped, or
   * null if the key is not associated or the associated object has
   * been deleted.
   */
  <T extends VersionedData> T get(VersionedDataKind<T> kind, String key);

  /**
   * Returns a {@link java.util.Map} of all associated objects of a given kind.
   *
   * @param <T> class of the objects that will be returned in the map
   * @param kind the kind of objects to get
   * @return a map of all associated object.
   */
  <T extends VersionedData> Map<String, T> all(VersionedDataKind<T> kind);

  /**
   * Initializes (or re-initializes) the store with the specified set of objects. Any existing entries
   * will be removed. Implementations can assume that this set of objects is up to date-- there is no
   * need to perform individual version comparisons between the existing objects and the supplied
   * features.
   * <p>
   * If possible, the store should update the entire data set atomically. If that is not possible, it
   * should iterate through the outer map and then the inner map <i>in the order provided</i> (the SDK
   * will use a Map subclass that has a defined ordering), storing each item, and then delete any
   * leftover items at the very end.
   * <p>
   * The store should not attempt to modify any of the Maps, and if it needs to retain the data in
   * memory it should copy the Maps.
   * 
   * @param allData all objects to be stored
   */
  void init(Map<VersionedDataKind<?>, Map<String, ? extends VersionedData>> allData);

  /**
   * Deletes the object associated with the specified key, if it exists and its version
   * is less than or equal to the specified version.
   *
   * @param <T> class of the object to be deleted
   * @param kind the kind of object to delete
   * @param key the key of the object to be deleted
   * @param version the version for the delete operation
   */
  <T extends VersionedData> void delete(VersionedDataKind<T> kind, String key, int version);

  /**
   * Update or insert the object associated with the specified key, if its version
   * is less than or equal to the version specified in the argument object.
   *
   * @param <T> class of the object to be updated
   * @param kind the kind of object to update
   * @param item the object to update or insert
   */
  <T extends VersionedData> void upsert(VersionedDataKind<T> kind, T item);

  /**
   * Returns true if this store has been initialized.
   *
   * @return true if this store has been initialized
   */
  boolean initialized();

}
