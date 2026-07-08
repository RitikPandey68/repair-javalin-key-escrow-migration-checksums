package com.example.keyescrow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the regulator-mandated key-escrow export. Mirrors the nested layout of
 * assets/key-escrow-export.json: escrowed keys (each carrying multiple historical
 * versions), the wrapping certificates that protected them, and the custody grants
 * that authorised access.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EscrowExport {

    public String exportId;
    public String jurisdiction;
    public List<EscrowedKey> escrowedKeys = new ArrayList<>();
    public List<WrappingCertificate> wrappingCertificates = new ArrayList<>();
    public List<CustodyGrant> custodyGrants = new ArrayList<>();
}
