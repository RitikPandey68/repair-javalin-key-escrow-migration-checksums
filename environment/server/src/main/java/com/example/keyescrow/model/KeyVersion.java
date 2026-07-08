package com.example.keyescrow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rotation of an escrowed key. The {@code version} field is a monotonically
 * increasing integer assigned at rotation time; {@code custodyStatus} records the
 * lifecycle state the regulator observed for that specific version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyVersion {

    public int version;
    public String algorithm;
    public String custodyStatus;
    public long createdEpoch;
}
