package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;
import com.launchdarkly.sdk.server.DataModel.WeightedVariation;

import org.apache.commons.codec.digest.DigestUtils;

final class EvaluatedVariation {
  private Integer index;
  private boolean inExperiment;
  
  EvaluatedVariation(Integer index, boolean inExperiment) {
    this.index = index;
    this.inExperiment = inExperiment;
  }

  public Integer getIndex() {
    return index;
  }

  public boolean isInExperiment() {
    return inExperiment;
  }
}

/**
 * Encapsulates the logic for percentage rollouts.
 */
abstract class EvaluatorBucketing {
  private EvaluatorBucketing() {}
  
  private static final float LONG_SCALE = (float) 0xFFFFFFFFFFFFFFFL;

  // Attempt to determine the variation index for a given user. Returns null if no index can be computed
  // due to internal inconsistency of the data (i.e. a malformed flag). 
  static EvaluatedVariation variationIndexForUser(DataModel.VariationOrRollout vr, LDUser user, String key, String salt) {
    Integer variation = vr.getVariation();
    if (variation != null) {
      return new EvaluatedVariation(variation, false);
    } else {
      DataModel.Rollout rollout = vr.getRollout();
      if (rollout != null && !rollout.getVariations().isEmpty()) {
        float bucket = bucketUser(rollout.getSeed(), user, key, rollout.getBucketBy(), salt);
        float sum = 0F;
        for (DataModel.WeightedVariation wv : rollout.getVariations()) {
          sum += (float) wv.getWeight() / 100000F;
          if (bucket < sum) {
            return new EvaluatedVariation(wv.getVariation(), vr.getRollout().isExperiment() && !wv.isUntracked());
          }
        }
        // The user's bucket value was greater than or equal to the end of the last bucket. This could happen due
        // to a rounding error, or due to the fact that we are scaling to 100000 rather than 99999, or the flag
        // data could contain buckets that don't actually add up to 100000. Rather than returning an error in
        // this case (or changing the scaling, which would potentially change the results for *all* users), we
        // will simply put the user in the last bucket.
        WeightedVariation lastVariation = rollout.getVariations().get(rollout.getVariations().size() - 1);
        return new EvaluatedVariation(lastVariation.getVariation(), vr.getRollout().isExperiment() && !lastVariation.isUntracked());
      }
    }
    return null;
  }

  static float bucketUser(Integer seed, LDUser user, String key, UserAttribute attr, String salt) {
    LDValue userValue = user.getAttribute(attr == null ? UserAttribute.KEY : attr);
    String idHash = getBucketableStringValue(userValue);
    if (idHash != null) {
      String prefix;
      if (seed != null) {
        prefix = seed.toString();
      } else {
        prefix = key + "." + salt;
      }
      if (user.getSecondary() != null) {
        idHash = idHash + "." + user.getSecondary();
      }
      String hash = DigestUtils.sha1Hex(prefix + "." + idHash).substring(0, 15);
      long longVal = Long.parseLong(hash, 16);
      return (float) longVal / LONG_SCALE;
    }
    return 0F;
  }

  private static String getBucketableStringValue(LDValue userValue) {
    switch (userValue.getType()) { 
    case STRING:
      return userValue.stringValue();
    case NUMBER:
      return userValue.isInt() ? String.valueOf(userValue.intValue()) : null;
    default:
      return null;
    }
  }
}
