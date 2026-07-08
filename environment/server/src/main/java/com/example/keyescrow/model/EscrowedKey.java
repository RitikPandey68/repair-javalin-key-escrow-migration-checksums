package com.example.keyescrow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A single escrowed key material identifier and the chain of versions it has held
 * over its lifetime. Historical versions are retained by the regulator, so the
 * export carries every rotation rather than only the currently-active material.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EscrowedKey {

    public String keyId;
    public List<KeyVersion> versions = new ArrayList<>();
}
