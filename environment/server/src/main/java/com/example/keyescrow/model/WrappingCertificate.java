package com.example.keyescrow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A wrapping certificate that protected escrowed key material in transit and at
 * rest. Each certificate is bound to exactly one escrowed key via {@code keyId}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WrappingCertificate {

    public String certId;
    public String subject;
    public String keyId;
    public long notAfterEpoch;
}
