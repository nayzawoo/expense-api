package com.nayzawoo.expense.identity;

import java.util.Map;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
class SessionRevocationService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    SessionRevocationService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    void revokeAllForEmail(String email) {
        Map<String, ? extends Session> matching =
                sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, email);
        for (String sessionId : matching.keySet()) {
            sessions.deleteById(sessionId);
        }
    }
}
