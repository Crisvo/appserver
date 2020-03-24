package ro.lic.server.websocket.utils;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ro.lic.server.model.Roles;

import java.io.IOException;

public class UserSession {

    private static final Logger log = LoggerFactory.getLogger(UserSession.class);

    private final String username;
    private final WebSocketSession session;
    private final Roles role;

    private RecordMediaPipeline recordMediaPipeline = null;

    public UserSession(WebSocketSession session, String name, Roles role) {
        this.session = session;
        this.username = name;
        this.role = role;

    }

    public Roles getRole() {
        return role;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public String getUsername() {
        return username;
    }

    public void sendMessage(JsonObject message) throws IOException {
        log.debug("Sending message from user '{}': {}", username, message);
        session.sendMessage(new TextMessage(message.toString()));
    }

    public String getSessionId() {
        return session.getId();
    }

    public void setRecordMediaPipeline(RecordMediaPipeline recordMediaPipeline) {
        this.recordMediaPipeline = recordMediaPipeline;
    }

    public void addCandidateRec(IceCandidate candidate){
        recordMediaPipeline.addCandidate(candidate);
    }

    public void addCandidateLive(IceCandidate candidate){
        recordMediaPipeline.addLiveCandidate(candidate, this);
    }

    public void release(){
        if(recordMediaPipeline != null)
            recordMediaPipeline.release();
    }
}

