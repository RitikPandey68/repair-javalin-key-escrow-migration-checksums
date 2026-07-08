package com.example.keyescrow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A custody grant authorising a named principal to exercise custody over a wrapping
 * certificate. Grants the regulator later withdrew carry {@code revoked = true};
 * per the remediation report these must not appear in the emitted custody manifest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustodyGrant {

    public String grantId;
    public String principal;
    public String certId;
    public boolean revoked;
    public long grantedEpoch;
}
