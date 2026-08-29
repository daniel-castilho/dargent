package io.dargent.payments.domain.port.out;

import java.util.UUID;

/** Audit writer port (E3 spec §5.1, design.md §5.1): minimal command audit trail. */
public interface AuditWriter {

    /** Records a command execution in the audit log. */
    void record(String commandName, UUID actorKeyId, UUID merchantId, String aggregateId, String requestId);
}