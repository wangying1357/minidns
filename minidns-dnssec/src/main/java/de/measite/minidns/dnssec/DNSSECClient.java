/*
 * Copyright 2015-2016 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package de.measite.minidns.dnssec;

import de.measite.minidns.DNSCache;
import de.measite.minidns.DNSMessage;
import de.measite.minidns.DNSName;
import de.measite.minidns.Question;
import de.measite.minidns.Record;
import de.measite.minidns.Record.CLASS;
import de.measite.minidns.Record.TYPE;
import de.measite.minidns.dnssec.UnverifiedReason.NoSecureEntryPointReason;
import de.measite.minidns.dnssec.UnverifiedReason.NoActiveSignaturesReason;
import de.measite.minidns.dnssec.UnverifiedReason.NoSignaturesReason;
import de.measite.minidns.dnssec.UnverifiedReason.NoTrustAnchorReason;
import de.measite.minidns.record.DLV;
import de.measite.minidns.record.DNSKEY;
import de.measite.minidns.record.DS;
import de.measite.minidns.record.RRSIG;
import de.measite.minidns.recursive.ReliableDNSClient;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DNSSECClient extends ReliableDNSClient {
    private static final BigInteger rootEntryKey = new BigInteger("03010001a80020a95566ba42e886bb804cda84e47ef56dbd7aec612615552cec906d2116d0ef207028c51554144dfeafe7c7cb8f005dd18234133ac0710a81182ce1fd14ad2283bc83435f9df2f6313251931a176df0da51e54f42e604860dfb359580250f559cc543c4ffd51cbe3de8cfd06719237f9fc47ee729da06835fa452e825e9a18ebc2ecbcf563474652c33cf56a9033bcdf5d973121797ec8089041b6e03a1b72d0a735b984e03687309332324f27c2dba85e9db15e83a0143382e974b0621c18e625ecec907577d9e7bade95241a81ebbe8a901d4d3276e40b114c0a2e6fc38d19c2e6aab02644b2813f575fc21601e0dee49cd9ee96a43103e524d62873d", 16);

    private static final DNSName DEFAULT_DLV = DNSName.from("dlv.isc.org");

    /**
     * Create a new DNSSEC aware DNS client using the global default cache.
     */
    public DNSSECClient() {
        this(DEFAULT_CACHE);
    }

    /**
     * Create a new DNSSEC aware DNS client with the given DNS cache.
     *
     * @param cache The backend DNS cache.
     */
    public DNSSECClient(DNSCache cache) {
        super(cache);
        addSecureEntryPoint(DNSName.EMPTY, rootEntryKey.toByteArray());
    }

    private Verifier verifier = new Verifier();

    /**
     * Known secure entry points (SEPs).
     */
    private final Map<DNSName, byte[]> knownSeps = new ConcurrentHashMap<>();

    private boolean stripSignatureRecords = true;

    /**
     * The active DNSSEC Look-aside Validation Registry. May be <code>null</code>.
     */
    private DNSName dlv;

    @Override
    public DNSMessage query(Question q) throws IOException {
        return queryDnssec(q);
    }

    public DNSSECMessage queryDnssec(CharSequence name, TYPE type) throws IOException {
        Question q = new Question(name, type, CLASS.IN);
        DNSMessage dnsMessage = super.query(q);
        DNSSECMessage dnssecMessage = performVerification(q, dnsMessage);
        return dnssecMessage;
    }

    public DNSSECMessage queryDnssec(Question q) throws IOException {
        DNSMessage dnsMessage = super.query(q);
        DNSSECMessage dnssecMessage = performVerification(q, dnsMessage);
        return dnssecMessage;
    }

    private DNSSECMessage performVerification(Question q, DNSMessage dnsMessage) throws IOException {
        if (dnsMessage == null) return null;

        // At this state, a DNSMessage is never authentic!
        if (dnsMessage.authenticData) {
            dnsMessage = dnsMessage.asBuilder().setAuthenticData(false).build();
        }

        Set<UnverifiedReason> unverifiedReasons = verify(dnsMessage);

        return createDnssecMessage(dnsMessage, unverifiedReasons);
    }

    private DNSSECMessage createDnssecMessage(DNSMessage dnsMessage, Set<UnverifiedReason> result) {
        List<Record> answers = dnsMessage.answerSection;
        List<Record> nameserverRecords = dnsMessage.authoritySection;
        List<Record> additionalResourceRecords = dnsMessage.additionalSection;
        Set<Record> signatures = new HashSet<>();
        extractSignatureRecords(signatures, answers);
        extractSignatureRecords(signatures, nameserverRecords);
        extractSignatureRecords(signatures, additionalResourceRecords);
        DNSMessage.Builder messageBuilder = dnsMessage.asBuilder();
        if (stripSignatureRecords) {
            messageBuilder.setAnswers(stripSignatureRecords(answers));
            messageBuilder.setNameserverRecords(stripSignatureRecords(nameserverRecords));
            messageBuilder.setAdditionalResourceRecords(stripSignatureRecords(additionalResourceRecords));
        }
        return new DNSSECMessage(messageBuilder, signatures, result);
    }

    private static void extractSignatureRecords(Set<Record> signatures, Collection<Record> records) {
        for (Record record : records) {
            if (record.type == TYPE.RRSIG)
                signatures.add(record);
        }
    }

    private static List<Record> stripSignatureRecords(List<Record> records) {
        if (records.isEmpty()) return records;
        List<Record> recordList = new ArrayList<>(records.size());
        for (Record record : records) {
            if (record.type != TYPE.RRSIG) {
                recordList.add(record);
            }
        }
        return recordList;
    }

    @Override
    protected boolean isResponseCacheable(Question q, DNSMessage dnsMessage) {
        return super.isResponseCacheable(q, dnsMessage);
    }

    private Set<UnverifiedReason> verify(DNSMessage dnsMessage) throws IOException {
        if (!dnsMessage.answerSection.isEmpty()) {
            return verifyAnswer(dnsMessage);
        } else {
            return verifyNsec(dnsMessage);
        }
    }

    private Set<UnverifiedReason> verifyAnswer(DNSMessage dnsMessage) throws IOException {
        Question q = dnsMessage.questions.get(0);
        List<Record> answers = dnsMessage.answerSection;
        List<Record> toBeVerified = dnsMessage.copyAnswers();
        VerifySignaturesResult verifiedSignatures = verifySignatures(q, answers, toBeVerified);
        Set<UnverifiedReason> result = verifiedSignatures.reasons;
        if (!result.isEmpty()) {
            return result;
        }

        // Keep SEPs separated, we only need one valid SEP.
        boolean sepSignatureValid = false;
        Set<UnverifiedReason> sepReasons = new HashSet<>();
        for (Iterator<Record> iterator = toBeVerified.iterator(); iterator.hasNext(); ) {
            Record record = iterator.next();
            if (record.type == TYPE.DNSKEY) {
                DNSKEY dnskey = (DNSKEY) record.payloadData;
                if (!dnskey.isSecureEntryPoint()) {
                    continue;
                }

                Set<UnverifiedReason> reasons = verifySecureEntryPoint(q, record);
                if (reasons.isEmpty()) {
                    sepSignatureValid = true;
                } else {
                    sepReasons.addAll(reasons);
                }
                if (!verifiedSignatures.sepSignaturePresent) {
                    LOGGER.finer("SEP key is not self-signed.");
                }
                iterator.remove();
            }
        }

        if (verifiedSignatures.sepSignaturePresent && !sepSignatureValid) {
            result.addAll(sepReasons);
        }
        if (verifiedSignatures.sepSignatureRequired && !verifiedSignatures.sepSignaturePresent) {
            result.add(new NoSecureEntryPointReason(q.name.ace));
        }
        if (!toBeVerified.isEmpty()) {
            if (toBeVerified.size() != answers.size()) {
                throw new DNSSECValidationFailedException(q, "Only some records are signed!");
            } else {
                result.add(new NoSignaturesReason(q));
            }
        }
        return result;
    }

    private Set<UnverifiedReason> verifyNsec(DNSMessage dnsMessage) throws IOException {
        Set<UnverifiedReason> result = new HashSet<>();
        Question q = dnsMessage.questions.get(0);
        boolean validNsec = false;
        boolean nsecPresent = false;
        DNSName zone = null;
        List<Record> nameserverRecords = dnsMessage.authoritySection;
        for (Record nameserverRecord : nameserverRecords) {
            if (nameserverRecord.type == TYPE.SOA)
                zone = nameserverRecord.name;
        }
        if (zone == null)
            throw new DNSSECValidationFailedException(q, "NSECs must always match to a SOA");
        for (Record record : nameserverRecords) {
            UnverifiedReason reason;

            switch (record.type) {
            case NSEC:
                nsecPresent = true;
                reason = verifier.verifyNsec(record, q);
                break;
            case NSEC3:
                nsecPresent = true;
                reason = verifier.verifyNsec3(zone, record, q);
                break;
            default:
                continue;
            }

            if (reason != null) {
                result.add(reason);
            } else {
                validNsec = true;
            }
        }
        if (nsecPresent && !validNsec) {
            throw new DNSSECValidationFailedException(q, "Invalid NSEC!");
        }
        List<Record> toBeVerified = dnsMessage.copyAuthority();
        VerifySignaturesResult verifiedSignatures = verifySignatures(q, nameserverRecords, toBeVerified);
        if (validNsec && verifiedSignatures.reasons.isEmpty()) {
            result.clear();
        } else {
            result.addAll(verifiedSignatures.reasons);
        }
        if (!toBeVerified.isEmpty() && toBeVerified.size() != nameserverRecords.size()) {
            throw new DNSSECValidationFailedException(q, "Only some nameserver records are signed!");
        }
        return result;
    }

    private class VerifySignaturesResult {
        boolean sepSignatureRequired = false;
        boolean sepSignaturePresent = false;
        Set<UnverifiedReason> reasons = new HashSet<>();
    }

    private VerifySignaturesResult verifySignatures(Question q, Collection<Record> reference, List<Record> toBeVerified) throws IOException {
        final Date now = new Date();
        final List<RRSIG> outdatedRrSigs = new LinkedList<>();
        VerifySignaturesResult result = new VerifySignaturesResult();
        final List<Record> rrsigs = new ArrayList<>(toBeVerified.size());

        for (Record record : toBeVerified) {
            if (record.type != TYPE.RRSIG) continue;
            RRSIG rrsig = (RRSIG) record.payloadData;

            if (rrsig.signatureExpiration.compareTo(now) < 0 || rrsig.signatureInception.compareTo(now) > 0) {
                // This RRSIG is out of date, but there might be one that is not.
                outdatedRrSigs.add(rrsig);
                continue;
            }
            rrsigs.add(record);
        }

        if (rrsigs.isEmpty()) {
            if (!outdatedRrSigs.isEmpty()) {
                result.reasons.add(new NoActiveSignaturesReason(q, outdatedRrSigs));
            } else {
                result.reasons.add(new NoSignaturesReason(q));
            }
            return result;
        }

        for (Record sigRecord : rrsigs) {
            RRSIG rrsig = (RRSIG) sigRecord.payloadData;

            List<Record> records = new ArrayList<>(reference.size());
            for (Record record : reference) {
                if (record.type == rrsig.typeCovered && record.name.equals(sigRecord.name)) {
                    records.add(record);
                }
            }

            result.reasons.addAll(verifySignedRecords(q, rrsig, records));

            if (q.name.equals(rrsig.signerName) && rrsig.typeCovered == TYPE.DNSKEY) {
                for (Iterator<Record> iterator = records.iterator(); iterator.hasNext(); ) {
                    DNSKEY dnskey = (DNSKEY) iterator.next().payloadData;
                    if (dnskey.isSecureEntryPoint()) {
                        // SEPs are verified separately, so don't mark them verified now.
                        iterator.remove();
                        if (dnskey.getKeyTag() == rrsig.keyTag) {
                            result.sepSignaturePresent = true;
                        }
                    }
                }
                // DNSKEY's should be signed by a SEP
                result.sepSignatureRequired = true;
            }

            if (!isParentOrSelf(sigRecord.name.ace, rrsig.signerName.ace)) {
                LOGGER.finer("Records at " + sigRecord.name + " are cross-signed with a key from " + rrsig.signerName);
            } else {
                toBeVerified.removeAll(records);
            }
            toBeVerified.remove(sigRecord);
        }
        return result;
    }

    private static boolean isParentOrSelf(String child, String parent) {
        if (child.equals(parent)) return true;
        if (parent.isEmpty()) return true;
        String[] childSplit = child.split("\\.");
        String[] parentSplit = parent.split("\\.");
        if (parentSplit.length > childSplit.length) return false;
        for (int i = 1; i <= parentSplit.length; i++) {
            if (!parentSplit[parentSplit.length - i].equals(childSplit[childSplit.length - i])) {
                return false;
            }
        }
        return true;
    }

    private Set<UnverifiedReason> verifySignedRecords(Question q, RRSIG rrsig, List<Record> records) throws IOException {
        Set<UnverifiedReason> result = new HashSet<>();
        DNSKEY dnskey = null;
        if (rrsig.typeCovered == TYPE.DNSKEY) {
            // Key must be present
            for (Record record : records) {
                if (record.type == TYPE.DNSKEY && ((DNSKEY) record.payloadData).getKeyTag() == rrsig.keyTag) {
                    dnskey = (DNSKEY) record.payloadData;
                }
            }
        } else if (q.type == TYPE.DS && rrsig.signerName.equals(q.name)) {
            // We should not probe for the self signed DS negative response, as it will be an endless loop.
            result.add(new NoTrustAnchorReason(q.name.ace));
            return result;
        } else {
            DNSSECMessage dnskeyRes = queryDnssec(rrsig.signerName, TYPE.DNSKEY);
            if (dnskeyRes == null) {
                throw new DNSSECValidationFailedException(q, "There is no DNSKEY " + rrsig.signerName + ", but it is used");
            }
            result.addAll(dnskeyRes.getUnverifiedReasons());
            for (Record record : dnskeyRes.answerSection) {
                if (record.type == TYPE.DNSKEY && ((DNSKEY) record.payloadData).getKeyTag() == rrsig.keyTag) {
                    dnskey = (DNSKEY) record.payloadData;
                }
            }
        }
        if (dnskey == null) {
            throw new DNSSECValidationFailedException(q, records.size() + " " + rrsig.typeCovered + " record(s) are signed using an unknown key.");
        }
        UnverifiedReason unverifiedReason = verifier.verify(records, rrsig, dnskey);
        if (unverifiedReason != null) {
            result.add(unverifiedReason);
        }
        return result;
    }

    private Set<UnverifiedReason> verifySecureEntryPoint(Question q, Record sepRecord) throws IOException {
        // The given RR must be a DNSKEY.
        final DNSKEY dnskey = (DNSKEY) sepRecord.payloadData;

        Set<UnverifiedReason> unverifiedReasons = new HashSet<>();
        Set<UnverifiedReason> activeReasons = new HashSet<>();
        if (knownSeps.containsKey(sepRecord.name)) {
            if (dnskey.keyEquals(knownSeps.get(sepRecord.name))) {
                return unverifiedReasons;
            } else {
                throw new DNSSECValidationFailedException(q, "Secure entry point " + sepRecord.name + " is in list of known SEPs, but mismatches response!");
            }
        }

        // If we are looking for the SEP of the root zone at this point, then the client was not
        // configured with one and we can abort stating the reason.
        if (sepRecord.name.isRootLabel()) {
           unverifiedReasons.add(new UnverifiedReason.NoRootSecureEntryPointReason());
           return unverifiedReasons;
        }

        DS delegation = null;
        DNSSECMessage dsResp = queryDnssec(sepRecord.name, TYPE.DS);
        if (dsResp == null) {
            LOGGER.fine("There is no DS record for " + sepRecord.name + ", server gives no result");
        } else {
            unverifiedReasons.addAll(dsResp.getUnverifiedReasons());
            for (Record record : dsResp.answerSection) {
               if (record.type != TYPE.DS) {
                   continue;
               }
               DS ds = (DS) record.payloadData;
               if (dnskey.getKeyTag() == ds.keyTag) {
                    delegation = ds;
                    activeReasons = dsResp.getUnverifiedReasons();
                    break;
                }
            }
            if (delegation == null) {
                LOGGER.fine("There is no DS record for " + sepRecord.name + ", server gives empty result");
            }
        }

        if (delegation == null && dlv != null && !dlv.isChildOf(sepRecord.name)) {
            DNSSECMessage dlvResp = queryDnssec(DNSName.from(sepRecord.name, dlv), TYPE.DLV);
            if (dlvResp != null) {
                unverifiedReasons.addAll(dlvResp.getUnverifiedReasons());
                for (Record record : dlvResp.answerSection) {
                    if (record.type == TYPE.DLV && ((DNSKEY) sepRecord.payloadData).getKeyTag() == ((DLV) record.payloadData).keyTag) {
                        LOGGER.fine("Found DLV for " + sepRecord.name + ", awesome.");
                        delegation = (DLV) record.payloadData;
                        activeReasons = dlvResp.getUnverifiedReasons();
                        break;
                    }
                }
            }
        }
        if (delegation != null) {
            UnverifiedReason unverifiedReason = verifier.verify(sepRecord, delegation);
            if (unverifiedReason != null) {
                unverifiedReasons.add(unverifiedReason);
            } else {
                unverifiedReasons = activeReasons;
            }
        } else {
            unverifiedReasons.add(new NoTrustAnchorReason(sepRecord.name.ace));
        }
        return unverifiedReasons;
    }

    @Override
    protected DNSMessage.Builder newQuestion(DNSMessage.Builder message) {
        message.getEdnsBuilder().setUdpPayloadSize(dataSource.getUdpPayloadSize()).setDnssecOk();
        message.setCheckingDisabled(true);
        return super.newQuestion(message);
    }

    @Override
    protected String isResponseAcceptable(DNSMessage response) {
        boolean dnssecOk = response.isDnssecOk();
        if (!dnssecOk) {
            // This is a deliberate violation of RFC 6840 § 5.6. I doubt that
            // "resolvers MUST ignore the DO bit in responses" does any good. Also we basically ignore the DO bit after
            // the fall back to iterative mode.
            return "DNSSEC OK (DO) flag not set in response";
        }
        boolean checkingDisabled = response.checkingDisabled;
        if (!checkingDisabled) {
            return "CHECKING DISABLED (CD) flag not set in response";
        }
        return super.isResponseAcceptable(response);
    }

    /**
     * Add a new secure entry point to the list of known secure entry points.
     *
     * A secure entry point acts as a trust anchor. By default, the only secure entry point is the key signing key
     * provided by the root zone.
     *
     * @param name The domain name originating the key. Once the secure entry point for this domain is requested,
     *             the resolver will use this key without further verification instead of using the DNS system to
     *             verify the key.
     * @param key  The secure entry point corresponding to the domain name. This key can be retrieved by requesting
     *             the DNSKEY record for the domain and using the key with first flags bit set
     *             (also called key signing key)
     */
    public void addSecureEntryPoint(DNSName name, byte[] key) {
        knownSeps.put(name, key);
    }

    /**
     * Remove the secure entry point stored for a domain name.
     *
     * @param name The domain name of which the corresponding secure entry point shall be removed. For the root zone,
     *             use the empty string here.
     */
    public void removeSecureEntryPoint(DNSName name) {
        knownSeps.remove(name);
    }

    /**
     * Clears the list of known secure entry points.
     *
     * This will also remove the secure entry point of the root zone and
     * thus render this instance useless until a new secure entry point is added.
     */
    public void clearSecureEntryPoints() {
        knownSeps.clear();
    }

    /**
     * Whether signature records (RRSIG) are stripped from the resulting {@link DNSMessage}.
     *
     * Default is {@code true}.
     *
     * @return Whether signature records are stripped.
     */
    public boolean isStripSignatureRecords() {
        return stripSignatureRecords;
    }

    /**
     * Enable or disable stripping of signature records (RRSIG) from the result {@link DNSMessage}.
     * @param stripSignatureRecords Whether signature records shall be stripped.
     */
    public void setStripSignatureRecords(boolean stripSignatureRecords) {
        this.stripSignatureRecords = stripSignatureRecords;
    }

    /**
     * Enables DNSSEC Lookaside Validation (DLV) using the default DLV service at dlv.isc.org.
     */
    public void enableLookasideValidation() {
        configureLookasideValidation(DEFAULT_DLV);
    }

    /**
     * Disables DNSSEC Lookaside Validation (DLV).
     * DLV is disabled by default, this is only required if {@link #enableLookasideValidation()} was used before.
     */
    public void disableLookasideValidation() {
        configureLookasideValidation(null);
    }

    /**
     * Enables DNSSEC Lookaside Validation (DLV) using the given DLV service.
     *
     * @param dlv The domain name of the DLV service to be used or {@code null} to disable DLV.
     */
    public void configureLookasideValidation(DNSName dlv) {
        this.dlv = dlv;
    }
}
