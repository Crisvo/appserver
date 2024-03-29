package ro.lic.server.websocket.utils.subscribe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;
import ro.lic.server.model.enums.Status;
import ro.lic.server.model.non_db_models.LiveWatcher;
import ro.lic.server.model.tables.User;
import ro.lic.server.websocket.utils.UserSession;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

@Component
public class SubscriberController {
    private static final Gson gson = new GsonBuilder().setDateFormat("MMM dd, yyyy, h:mm:ss a").setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().create();

    private List<UserSession> userListListener = new LinkedList<>();
    private List<UserSession> mapChangesListener = new LinkedList<>();
    private List<UserSession> liveStreamerListener = new LinkedList<>();

    public void addUserListListener(UserSession session){
        userListListener.add(session);
    }

    public void removeUserListListener(UserSession session){
        userListListener.remove(session);
    }

    public void addMapChangesSubscriber(UserSession session) {
        mapChangesListener.add(session);
    }

    public void removeMapChangesSubscriber(UserSession session){
        mapChangesListener.remove(session);
    }

    public void addLiveStreamerListener(UserSession session){
        liveStreamerListener.add(session);
    }

    public void removeLiveStreamerListener(UserSession session){
        liveStreamerListener.remove(session);
    }

    private void notifySubscribers(JsonObject message, List<UserSession> list){
        if(list.isEmpty())
            return;

        for(UserSession session : list){
            synchronized (session.getSession()){
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void removeSubscriberAfterConnectionClosed(UserSession session){
        userListListener.remove(session);
        liveStreamerListener.remove(session);
        mapChangesListener.remove(session);
    }

    public void notifySubscribersOnUserModified(User modifiedUser){
        JsonObject message = new JsonObject();

        message.addProperty("method", "subscribe");
        message.addProperty("event", "userUpdated");
        message.addProperty("payload", gson.toJson(modifiedUser));

        notifySubscribers(message, userListListener);
    }

    public void notifySubscribersOnUserStatusModified(Status status, String username){
        JsonObject message = new JsonObject();

        message.addProperty("method", "subscribe");
        message.addProperty("event", "userStatus");
        message.addProperty("status", status.name());
        message.addProperty("username", username);

        notifySubscribers(message, userListListener);
    }

    public void notifySubscribersOnLocationChanged(String username, double lat, double lng){
        JsonObject message = new JsonObject();

        message.addProperty("method", "subscribe");
        message.addProperty("event", "mapItemLocation");
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("lat", lat);
        payload.addProperty("lng", lng);
        message.add("payload", payload);

        notifySubscribers(message, mapChangesListener);
    }

    public void notifySubscribersOnLiveStreamingStarted(LiveWatcher liveWatcher){
        JsonObject message = new JsonObject();

        message.addProperty("method", "subscribe");
        message.addProperty("event", "liveStreamers");
        message.addProperty("status", "started");
        message.addProperty("payload", gson.toJson(liveWatcher));

        notifySubscribers(message, liveStreamerListener);
    }

    public void notifySubscribersOnLiveStreamingStopped(LiveWatcher liveWatcher){
        JsonObject message = new JsonObject();

        message.addProperty("method", "subscribe");
        message.addProperty("event", "liveStreamers");
        message.addProperty("status", "stopped");
        message.addProperty("payload", gson.toJson(liveWatcher));

        notifySubscribers(message, liveStreamerListener);
    }
}
