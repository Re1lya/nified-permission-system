package com.cdwy.permission.platform;

import com.cdwy.permission.WorkflowTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;

class AuditRetentionWorkflowIntegrationTests extends WorkflowTestSupport {
    @Autowired AuditRetention retention;

    private long oldEvent(String target) {
        db.update("insert into audit_event(actor_id,department_id,kind,target,result,created_at) values(?,?,'ARCHIVE_TEST',?,'SUCCESS',?)",
            user, department, target, Instant.now().minusSeconds(400L * 86400));
        return db.queryForObject("select id from audit_event where target=?", Long.class, target);
    }

    @Test void archivePreservesAuditFieldsAndLeavesRecentRecordsInPlace() {
        long old = oldEvent("old" + suffix);
        db.update("insert into audit_event(actor_id,department_id,kind,target,result,created_at) values(?,?,'ARCHIVE_TEST',?,'SUCCESS',?)",
            user, department, "recent" + suffix, Instant.now());
        retention.archive(); retention.archive();
        assertEquals(0, count("select count(*) from audit_event where id=?", old));
        assertEquals(1, count("select count(*) from audit_archive where id=? and actor_id=? and department_id=? and target=? and result='SUCCESS' and archived_at is not null",
            old, user, department, "old" + suffix));
        assertEquals(1, count("select count(*) from audit_event where target=?", "recent" + suffix));
    }

    @Test void archiveConflictRollsBackWholeBatchInsteadOfLosingAuditRecords() {
        long first = oldEvent("first" + suffix), second = oldEvent("second" + suffix);
        db.update("insert into audit_archive(id,actor_id,kind,target,result,created_at,archived_at) select id,actor_id,kind,target,result,created_at,? from audit_event where id=?", Instant.now(), second);
        try {
            assertThrows(DataIntegrityViolationException.class, retention::archive);
            assertEquals(2, count("select count(*) from audit_event where id in (?,?)", first, second));
            assertEquals(0, count("select count(*) from audit_archive where id=?", first), "后续记录冲突时，前一条归档与删除均应回滚");
        } finally {
            db.update("delete from audit_archive where id=?", second);
            db.update("delete from audit_event where id in (?,?)", first, second);
        }
    }
}
