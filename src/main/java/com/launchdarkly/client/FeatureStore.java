package com.launchdarkly.client;

import java.util.Map;

/**
 * A thread-safe, versioned store for {@link com.launchdarkly.client.FeatureRep} objects.
 * Implementations should permit concurrent access and updates.
 *
 * Delete and upsert requests are versioned-- if the version number in the request is less than
 * the currently stored version of the feature, the request should be ignored.
 *
 * These semantics support the primary use case for the store, which synchronizes a collection
 * of features based on update messages that may be received out-of-order.
 *
 */
public interface FeatureStore {
  /**
   *
   * Returns the {@link com.launchdarkly.client.FeatureRep} to which the specified key is mapped, or
   * null if the key is not associated or the associated {@link com.launchdarkly.client.FeatureRep} has
   * been deleted.
   *
   * @param key the key whose associated {@link com.launchdarkly.client.FeatureRep} is to be returned
   * @return the {@link com.launchdarkly.client.FeatureRep} to which the specified key is mapped, or
   * null if the key is not associated or the associated {@link com.launchdarkly.client.FeatureRep} has
   * been deleted.
   */
  FeatureRep<?> get(String key);

  /**
   * Returns a {@link java.util.Map} of all associated features.
   *
   *
   * @return a map of all associated features.
   */
  Map<String, FeatureRep<?>> all();

  /**
   * Initializes (or re-initializes) the store with the specified set of features. Any existing entries
   * will be removed. Implementations can assume that this set of features is up to date-- there is no
   * need to perform individual version comparisons between the existing features and the supplied
   * features.
   *
   *
   * @param features the features to set the store
   */
  void init(Map<String, FeatureRep<?>> features);

  /**
   *
   * Deletes the feature associated with the specified key, if it exists and its version
   * is less than or equal to the specified version.
   *
   * @param key the key of the feature to be deleted
   * @param version the version for the delete operation
   */
  void delete(String key, int version);

  /**
   * Update or insert the feature associated with the specified key, if its version
   * is less than or equal to the version specified in the argument feature.
   *
   * @param key
   * @param feature
   */
  void upsert(String key, FeatureRep<?> feature);

  /**
   * Returns true if this store has been initialized (i.e. if {@link #init(java.util.Map)} has been called)
   *
   * @return true if this store has been initialized
   */
  boolean initialized();
}
