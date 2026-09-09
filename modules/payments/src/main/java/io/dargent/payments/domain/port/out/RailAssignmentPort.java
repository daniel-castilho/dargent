package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;

/**
 * Infrastructure-routing seam (M5 S0): which rail a payment rides, persisted by the adapter next to
 * the payment row. The {@code Payment} aggregate deliberately does NOT carry the rail — routing is
 * infrastructure state, not domain state — so the port is owned outside the aggregate and answered
 * by the payments store.
 */
public interface RailAssignmentPort {

    /** @return the rail name persisted for the payment (never null; unknown rows default to "pix") */
    String railOf(Txid txid);

    /** Persists the rail for a freshly created payment (join the create transaction). */
    void assign(Txid txid, String rail);
}
