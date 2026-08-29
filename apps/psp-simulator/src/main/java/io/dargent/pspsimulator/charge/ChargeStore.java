package io.dargent.pspsimulator.charge;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The simulator's entire persistence story (E2 spec §3.1): a {@link ConcurrentHashMap} keyed by the
 * merchant's txid. {@code putIfAbsent} arbitrates the duplicate-txid race atomically — a racing
 * duplicate loses and the caller maps it to {@code 409 txid_already_exists}. A restart wipes charges;
 * documented and acceptable (the simulator's durability is nobody's problem but its own).
 */
public final class ChargeStore {

    private final ConcurrentHashMap<String, Charge> charges = new ConcurrentHashMap<>();

    /** @return the pre-existing charge if txid was already present, else null (new charge inserted) */
    public Charge putIfAbsent(Charge charge) {
        return charges.putIfAbsent(charge.txid(), charge);
    }

    public Charge get(String txid) {
        return charges.get(txid);
    }

    public int size() {
        return charges.size();
    }
}