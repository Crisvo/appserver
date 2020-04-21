package ro.lic.server.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.kurento.client.*;
import org.kurento.commons.exception.KurentoException;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ro.lic.server.model.Status;
import ro.lic.server.model.location.UserLocation;
import ro.lic.server.model.repository.ActionRepository;
import ro.lic.server.model.repository.UserRepository;
import ro.lic.server.model.repository.VideoRepository;
import ro.lic.server.model.tables.Action;
import ro.lic.server.model.tables.User;
import ro.lic.server.model.tables.Video;
import ro.lic.server.websocket.security.Authoriser;
import ro.lic.server.websocket.utils.*;
import ro.lic.server.websocket.utils.pipeline.PlayMediaPipeline;
import ro.lic.server.websocket.utils.pipeline.RecordMediaPipeline;
import ro.lic.server.websocket.utils.subscribe.SubscriberController;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAccumulator;

import static ro.lic.server.constants.JsonConstants.*;

public class EndPointHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EndPointHandler.class);
    private static final Gson gson = new GsonBuilder().setDateFormat("MMM dd, yyyy, h:mm:ss a").setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().create();

    private final ConcurrentHashMap<String, RecordMediaPipeline> recordPipeline = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayMediaPipeline> playPipelines = new ConcurrentHashMap<>();

    /** key is username*/
    private final ConcurrentHashMap<String, UserLocation> userLocations = new ConcurrentHashMap<>();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ActionRepository actionRepository;

    @Autowired(required = true)
    private KurentoClient kurento;

    @Autowired(required = true)
    private UserRegistry registry = new UserRegistry();

    @Autowired
    private SubscriberController subscriberController;



    @Override
    public void afterConnectionClosed(final WebSocketSession session, CloseStatus status) throws Exception {
        String name = registry.getBySession(session).getUsername();

        User user = userRepository.getUser(name);
        actionRepository.userLogout(user);
        userRepository.setUserOffline(user.getUsername());
        subscriberController.notifySubscribersOnUserStatusModified(Status.OFFLINE, user.getUsername());
        stop(session);
        registry.removeBySession(session);
        System.out.println(String.format("User %s disconnected!", name));
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        super.handlePongMessage(session, message);
    }

    @Override
    public void afterConnectionEstablished(final WebSocketSession session) throws Exception {
        String username = session.getPrincipal().getName();
        username = username.substring(username.indexOf("[") + 1, username.indexOf("]"));

        // check if user is already connected
        if (registry.exists(username)) {
            session.close(CloseStatus.SERVER_ERROR);
            // todo: set log error
        } else {
            System.out.println(String.format("User %s connected!", username));
            UserSession userSession = new UserSession(session, username, userRepository.getUserRoleByUsername(username));
            registry.register(userSession);

            User user = userRepository.getUser(username);
            actionRepository.userLogin(user);
            subscriberController.notifySubscribersOnUserStatusModified(Status.ONLINE, username);
        }
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonObject receivedMessage = gson.fromJson(message.getPayload(), JsonObject.class);
            UserSession user = registry.getBySession(session);

            if (user != null) {
                System.out.println(String.format("Incoming message from user '%s': %s", user.getUsername(), receivedMessage));
            } else {
                System.out.println(String.format("Incoming message from unknown user: %s}", receivedMessage));
                return;
            }

            switch (receivedMessage.get("method").getAsString()) {
                case "update":
                    handleUpdateMethodMessage(receivedMessage, user);
                    break;
                case "request":
                    handleRequestMethodMessage(user, receivedMessage);
                    break;
                case "media":
                    handleMediaMethodMessage(user, receivedMessage);
                    break;
                case "subscribe":
                    handleSubscribeMethodMessage(user, receivedMessage);
                    break;
                case "unsubscribe":
                    handleUnsubscribeMethodMessage(user, receivedMessage);
                    break;
                default:

            }
        } catch (JsonSyntaxException | NullPointerException e) {
            session.sendMessage(new TextMessage("json error"));
            System.out.println("!!! JSON FROM CLIENT ERROR: " + e.getMessage());
        }
    }

    //region Update method message
    private void handleUpdateMethodMessage(JsonObject receivedMessage, UserSession session) throws IOException {
        switch (receivedMessage.get("event").getAsString()) {
            case "enroll":
                handleEnrollEvent(session, receivedMessage);
                break;
            case "updateUser":
                handleUpdateUserEvent(session, receivedMessage);
                break;
            case "disableUser":
                handleDisableUserEvent(session, receivedMessage);
                break;
            case "removeVideo":
                break;
            case "mapItems":
                break;
            case "location":
                handleLocationEvent(session, receivedMessage);
                break;
            default:

        }
    }

    /***/
    private void handleEnrollEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        if (Authoriser.authoriseEnroll(userSession)) {
            System.out.println("Enroll event: " + receivedMessage.toString());
            String userJson = receivedMessage.get("payload").getAsString();
            if (userJson != null) {
                User userModel = User.fromJson(userJson);
                //encode password
                userModel.setStatus(Status.OFFLINE.name());

                User user = userRepository.getUser(userSession.getUsername());
                actionRepository.onEnrolledUser(user);

                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                userModel.setPassword(encoder.encode(userModel.getPassword()));
                userRepository.addUser(userModel);

                JsonObject response = new JsonObject();
                response.addProperty("method", "update");
                response.addProperty("event", "enroll");
                response.addProperty("response", "success");

                synchronized (userSession) {
                    userSession.sendMessage(response);
                }
            }
        }
    }

    /***/
    private void handleUpdateUserEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        if (Authoriser.authoriseEditUser(userSession)) {
            User userTarget = User.fromJson(receivedMessage.get("payload").getAsString());

            User user = userRepository.getUser(userSession.getUsername());
            actionRepository.onEditUser(user, userTarget);

            int i = userRepository.updateUser(userTarget);
            System.out.println("Updated rows: " + i);

            JsonObject response = new JsonObject();
            response.addProperty("method", "update");
            response.addProperty("event", "updateUser");
            if (i != 0) {
                response.addProperty("response", "success");
                subscriberController.notifySubscribersOnUserModified(userTarget);
            }
            else
                response.addProperty("response", "fail");

            synchronized (userSession) {
                userSession.sendMessage(response);
            }
        }
    }

    /***/
    private void handleDisableUserEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        String userTargetUsername = receivedMessage.get("payload").getAsString();

        User admin = userRepository.getUser(userSession.getUsername());
        actionRepository.onDisabledUser(admin, userTargetUsername);
        userRepository.disableUser(userTargetUsername);
        System.out.println(String.format("Disabled account %s by %s", userTargetUsername, admin.getUsername()));

        subscriberController.notifySubscribersOnUserStatusModified(Status.DISABLED, userTargetUsername);

        JsonObject response = new JsonObject();

        response.addProperty("method", "update");
        response.addProperty("event", "disableUser");
        response.addProperty("response", "success");

        synchronized (userSession) {
            userSession.sendMessage(response);
        }
    }

    private void handleRemoveVideoEvent(UserSession userSession, JsonObject receivedMessage) {

    }

    private void handleMapItemEvent(UserSession userSession, JsonObject receivedMessage) {

    }

    private void handleLocationEvent(UserSession userSession, JsonObject receivedMessage) {
        Double lat = receivedMessage.get("payload").getAsJsonObject().get("lat").getAsDouble();
        Double lng = receivedMessage.get("payload").getAsJsonObject().get("lng").getAsDouble();

        userLocations.put(userSession.getUsername(), new UserLocation(lat, lng));
        System.out.println(String.format("User %s, updated location lat: %f; lng: %f", userSession.getUsername(), lat, lng));
    }
    //endregion

    //region Request method message
    /***/
    private void handleRequestMethodMessage(UserSession userSession, JsonObject receivedMessage) throws IOException {
        switch (receivedMessage.get("event").getAsString()) {
            case "requestTimeline":
                handleRequestTimelineEvent(userSession, receivedMessage);
                break;
            case "requestRecordedVideos":
                handleRequestRecordedVideosEvent(userSession, receivedMessage);
                break;
            case "requestAllUsers":
                handleRequestAllUsersEvent(userSession, receivedMessage);
                break;
            case "requestOnlineUsers":
                handleRequestOnlineUsersEvent(userSession, receivedMessage);
                break;
            default:

        }
    }

    private void handleRequestTimelineEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        if (Authoriser.authoriseListTimeline(userSession)) {
            // target user
            String forUser = receivedMessage.get("user").getAsString();
            String dateString = receivedMessage.get("date").getAsString();
            User user = userRepository.getUser(forUser);

            List<Action> actions = actionRepository.getTimeLineForUserOnDate(user, dateString);

            JsonObject response = new JsonObject();
            response.addProperty("method", "request");
            response.addProperty("event", "requestTimeline");
            response.addProperty("payload", gson.toJson(actions));

            synchronized (userSession) {
                userSession.sendMessage(response);
            }
        }
    }

    private void handleRequestRecordedVideosEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        if (Authoriser.authoriseListRecordedVideos(userSession)) {
            String forUser = receivedMessage.get("user").getAsString();
            User user = userRepository.getUser(forUser);

            List<Video> videos = videoRepository.getListVideoForUser(user.getId());

            JsonObject response = new JsonObject();
            response.addProperty("method", "request");
            response.addProperty("event", "requestRecordedVideos");
            response.addProperty("payload", gson.toJson(videos));

            synchronized (userSession) {
                userSession.sendMessage(response);
            }
        }
    }

    private void handleRequestAllUsersEvent(UserSession userSession, JsonObject receivedMessage) throws IOException {
        if (Authoriser.authoriseListUsers(userSession)) {

            List<User> users = userRepository.getAllUsers();

            JsonObject response = new JsonObject();
            response.addProperty("method", "request");
            response.addProperty("event", "requestAllUsers");
            response.addProperty("payload", gson.toJson(users));

            synchronized (userSession) {
                userSession.sendMessage(response);
            }
        }
    }

    private void handleRequestOnlineUsersEvent(UserSession userSession, JsonObject receivedMessage) {
        if (Authoriser.authoriseListUsers(userSession)) {

        }
    }
    //endregion

    //region Media method message
    /**
     * Handles all media events
     */
    private void handleMediaMethodMessage(UserSession userSession, JsonObject receivedMessage) throws IOException {
        switch (receivedMessage.get("event").getAsString()) {
            case "iceCandidate":
                handleIceCandidateEvent(userSession, receivedMessage);
                break;
            case "playVideoRequest":
                handlePlayVideoRequestEvent(userSession, receivedMessage);
                break;
            case "pauseVideoRequest":
                handlePauseVideoRequestEvent(userSession, receivedMessage);
                break;
            case "resumeVideoRequest":
                handleResumeVideoRequestEvent(userSession, receivedMessage);
                break;
            case "getVideoPositionRequest":
                handleGetVideoPositionRequestEvent(userSession, receivedMessage);
                break;
            case "seekVideoRequest":
                handleSeekVideoRequestEvent(userSession, receivedMessage);
                break;
            case "stopVideoRequest":
                handleStopVideoRequestEvent(userSession, receivedMessage);
                break;
            case "startVideoStreamRequest":
                handleStartVideoStreamRequestEvent(userSession, receivedMessage);
                break;
            case "stopVideoStreamRequest":
                handleStopVideoSteramRequestEvent(userSession, receivedMessage);
                break;
            case "startLiveVideoWatch":
                handleLiveVideoWatchRequestEvent(userSession, receivedMessage);
                break;
            default:

        }
    }

    /**
     * This method handles ice candidates received from client.
     * <p> Ice candidates can be candidates for live streaming, playback or live watching</p>
     *
     * @param session         is the websocket open connection of the message sender
     * @param receivedMessage is the message received by the server
     */
    private void handleIceCandidateEvent(UserSession session, JsonObject receivedMessage) {
        JsonObject candidate = receivedMessage.get("candidate").getAsJsonObject();
        String iceFor = candidate.get("iceFor").getAsString();

        IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
                candidate.get("sdpMid").getAsString(),
                candidate.get("sdpMLineIndex").getAsInt());
        switch (iceFor) {
            case ICE_FOR_LIVE:
                session.addCandidateLive(cand);
                break;
            case ICE_FOR_PLAY:
                PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
                playMediaPipeline.addIceCandidate(cand);
                break;
            case ICE_FOR_REC:
                session.addCandidateRec(cand);
                break;
        }
    }

    //region Playback handlers
    /**
     * This method handle a play video request
     * <p> The application server sends a response which must contain:
     * <ul>
     *     <li> id: playResponse</li>
     *     <li> response: accepted (or rejected)</li>
     *     <li> session description offer (only if response is accepted)</li>
     * </ul></p>
     * <p> Also, the application server sends an ice candidate response, which must contain:
     * {@link IceCandidate}
     * <ul>
     *     <li> id: iceCandidate</li>
     *     <li> for: play</li>
     *     <li> the candidate</li>
     * </ul></p>
     * <p> When the peers are connected, in this case the user and Kurento Media Server, the application server
     * send the video parameters {@link VideoInfo}, the message contains:
     * <ul>
     *     <li> id: videoInfo </li>
     *     <li> isSeekable </li>
     *     <li> begin seekable </li>
     *     <li> end seekable </li>
     *     <li> duration </li>
     * </ul></p>
     *
     * @param session         is the user who has send the request
     * @param receivedMessage is the received message
     *                        <p>
     *                        should contain:
     *                        <ul>
     *                        <li> session description offer</li>
     *                        <li> video path: the path where video is save in media server</li>
     *
     *                        </ul></p>
     */
    private void handlePlayVideoRequestEvent(final UserSession session, final JsonObject receivedMessage) throws IOException {
        JsonObject response = new JsonObject();

        response.addProperty("method", "media");
        response.addProperty("event", "playVideoRequest");

        if (Authoriser.authorisePlayVideo(session)) {
            final User user = userRepository.getUser(session.getUsername());
            actionRepository.userStartedPlaybackVideo(user);


            String sdpOffer = receivedMessage.get("sdpOffer").getAsString();
            String mediaPath = receivedMessage.get("path").getAsString();

            // create media pipeline
            final PlayMediaPipeline playMediaPipeline = new PlayMediaPipeline(kurento, mediaPath, session.getSession());
            playPipelines.put(session.getSessionId(), playMediaPipeline);

            // add error listener
            playMediaPipeline.getPlayer().addErrorListener(new EventListener<ErrorEvent>() {
                @Override
                public void onEvent(ErrorEvent errorEvent) {
                    System.out.println("Player error event...");
                    actionRepository.userEndedPlaybackVideo(user);
                    playMediaPipeline.sendPlayEnd(session.getSession());
                    playPipelines.remove(session.getSessionId());
                }
            });
            playMediaPipeline.getPlayer().addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
                @Override
                public void onEvent(EndOfStreamEvent endOfStreamEvent) {
                    System.out.println("Player end of stream event...");
                    actionRepository.userEndedPlaybackVideo(user);
                    playMediaPipeline.sendPlayEnd(session.getSession());
                    playPipelines.remove(session.getSessionId());
                }
            });

            playMediaPipeline.getWebRtc().addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {
                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("method", "media");
                    response.addProperty("event", "iceCandidate");
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("for", SEND_ICE_FOR_PLAY);
                    candidate.add("candidate", JsonUtils
                            .toJsonObject(event.getCandidate()));
                    response.add("candidate", candidate);
                    synchronized (session.getSession()) {
                        try {
                            session.sendMessage(response);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });

            // get video info and send it to the user
            playMediaPipeline.getWebRtc().addMediaStateChangedListener(new EventListener<MediaStateChangedEvent>() {
                @Override
                public void onEvent(MediaStateChangedEvent mediaStateChangedEvent) {
                    System.out.println("Media state changed event...");
                    VideoInfo videoInfo = playMediaPipeline.getPlayer().getVideoInfo();

                    JsonObject response = new JsonObject();
                    response.addProperty("method", "media");
                    response.addProperty("event", "videoInfo");
                    response.addProperty("isSeekable", videoInfo.getIsSeekable());
                    response.addProperty("initSeekable", videoInfo.getSeekableInit());
                    response.addProperty("endSeekable", videoInfo.getSeekableEnd());
                    response.addProperty("videoDuration", videoInfo.getDuration());

                    synchronized (session) {
                        try {
                            session.sendMessage(response);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });

            // SDP negotiation
            String sdpAnswer = playMediaPipeline.generateSdpAnswer(sdpOffer);

            response.addProperty("response", "accepted");
            response.addProperty("sdpAnswer", sdpAnswer);

            // play
            playMediaPipeline.play();

            synchronized (session) {
                session.sendMessage(response);
            }

            // gather candidates
            playMediaPipeline.getWebRtc().gatherCandidates();

        } else {
            response.addProperty("response", "rejected");
            response.addProperty("reason", "notAuthorised");
            session.sendMessage(response);
        }
    }

    /**
     * This method handles the pause request made by the user.
     * In this case peers are still connected, method tells play media pipeline to pause the video transmission.
     *
     * @param session         is the user who requested the pause of the playback
     * @param receivedMessage is the message received by application server
     */
    private void handlePauseVideoRequestEvent(UserSession session, JsonObject receivedMessage) {
        PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
        if (playMediaPipeline == null)
            return;
        playMediaPipeline.getPlayer().pause();
    }

    /**
     * This method is used to resume the video playback, as the user requested it.
     * Method tells play media pipeline to resume video streaming. It sends again the video description parameters, see
     * {@link VideoInfo}
     *
     * @param session         is the user who requested playback resume.
     * @param receivedMessage is the message received by application server
     */
    private void handleResumeVideoRequestEvent(UserSession session, JsonObject receivedMessage) throws IOException {
        PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
        if (playMediaPipeline == null)
            return;
        playMediaPipeline.getPlayer().play();

        VideoInfo videoInfo = playMediaPipeline.getPlayer().getVideoInfo();

        JsonObject response = new JsonObject();
        response.addProperty("method", "media");
        response.addProperty("event", "videoInfo");
        response.addProperty("isSeekable", videoInfo.getIsSeekable());
        response.addProperty("initSeekable", videoInfo.getSeekableInit());
        response.addProperty("endSeekable", videoInfo.getSeekableEnd());
        response.addProperty("videoDuration", videoInfo.getDuration());

        synchronized (session) {
            session.sendMessage(response);
        }
    }

    /**
     * Method used to handle the current position request.
     * The application server send an response as following:
     * <ul>
     *     <li> id: getPositionResponse</li>
     *     <li> position: <currentPosition></li>
     * </ul>
     *
     * @param session         is the user who requested playback resume.
     * @param receivedMessage is the message received by application server
     */
    private void handleGetVideoPositionRequestEvent(UserSession session, JsonObject receivedMessage) throws IOException {
        PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
        if (playMediaPipeline != null && !playMediaPipeline.isStreamEnded()) {
            long currentPosition = playMediaPipeline.getPlayer().getPosition();

            JsonObject response = new JsonObject();
            response.addProperty("method", "media");
            response.addProperty("event", "getVideoPositionRequest");
            response.addProperty("position", currentPosition);

            synchronized (session.getSession()) {
                session.sendMessage(response);
            }
        }
    }

    /**
     * Method handle the seek video request. Tells the play media pipeline to go to the requested position.
     * If the seek fails, the server sends an information message to the user.
     *
     * @param session         is the user who requested playback resume.
     * @param receivedMessage is the message received by application server
     */
    private void handleSeekVideoRequestEvent(UserSession session, JsonObject receivedMessage) throws IOException {
        PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
        try {
            long position = receivedMessage.get("position").getAsLong();
            playMediaPipeline.getPlayer().setPosition(position);
        } catch (KurentoException e) {
            log.debug("The seek cannot be performed");
            JsonObject response = new JsonObject();
            response.addProperty("method", "media");
            response.addProperty("event", "seekVideoRequest");
            response.addProperty("message", "Seek failed");

            synchronized (session) {
                session.sendMessage(response);
            }
        }
    }

    /**
     * This method is used to handle stop video request.
     *
     * @param session         is the user who requested playback resume.
     * @param receivedMessage is the message received by application server
     */
    private void handleStopVideoRequestEvent(UserSession session, JsonObject receivedMessage) {
        User user = userRepository.getUser(session.getUsername());
        actionRepository.userEndedPlaybackVideo(user);

        PlayMediaPipeline playMediaPipeline = playPipelines.get(session.getSessionId());
        playMediaPipeline.getPlayer().stop();
    }

    /**
     * This method creates environment for recording the media streaming coming from user
     * It follow the forwarding steps:
     * <ul>
     *     <li> Creates the media logic (recording end point)</li>
     *     <li> sdp negotiation</li>
     *     <li> gathers ice candidates</li>
     *     <li> sends the response to the client (sdp answer)</li>
     * </ul>
     *
     * @param session         is the user who requested playback resume.
     * @param receivedMessage is the message received by application server
     */
    private void handleStartVideoStreamRequestEvent(UserSession session, JsonObject receivedMessage) throws IOException {
        String from = session.getUsername();
        String sdpOffer = receivedMessage.getAsJsonPrimitive("sdpOffer").getAsString();

        // media logic
        RecordMediaPipeline recordMediaPipeline = new RecordMediaPipeline(kurento, from);

        recordPipeline.put(session.getSessionId(), recordMediaPipeline);

        session.setRecordMediaPipeline(recordMediaPipeline);

        // sdp negotiating
        String sdpAnswer = recordMediaPipeline.generateSdpAnswerFromRecordingEp(sdpOffer);

        recordMediaPipeline.getRecordingWebRtcEndpoint().addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {
            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("method", "media");
                response.addProperty("event", "iceCandidate");
                JsonObject candidate = new JsonObject();
                candidate.addProperty("for", SEND_ICE_FOR_REC);
                candidate.add("candidate", JsonUtils
                        .toJsonObject(event.getCandidate()));
                response.add("candidate", candidate);
                try {
                    synchronized (session) {
                        session.sendMessage(response);
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });

        JsonObject response = new JsonObject();
        response.addProperty("method", "media");
        response.addProperty("event", "startVideoStreaming");
        response.addProperty("response", "accepted");
        response.addProperty("sdpAnswer", sdpAnswer);

        synchronized (session) {
            session.sendMessage(response);
        }

        recordMediaPipeline.getRecordingWebRtcEndpoint().gatherCandidates();

        recordMediaPipeline.getRecordingWebRtcEndpoint().addMediaFlowInStateChangeListener(new EventListener<MediaFlowInStateChangeEvent>() {
            @Override
            public void onEvent(MediaFlowInStateChangeEvent mediaFlowInStateChangeEvent) {
                System.out.println("Media flow incoming");
                recordMediaPipeline.record();
            }
        });

        recordMediaPipeline.getRecordingWebRtcEndpoint().addMediaFlowOutStateChangeListener(mediaFlowOutStateChangeEvent -> {
            System.out.println("Media out listener!");
            recordMediaPipeline.record();
        });


        User user = userRepository.getUser(session.getUsername());
        actionRepository.userStartedRecordingSession(user);
        Video video = new Video(recordMediaPipeline.getRecordingPath(), user, new Date());
        videoRepository.addVideo(video);
    }

    /**
     * Handles the stop request from user client application
     */
    private void handleStopVideoSteramRequestEvent(UserSession session, JsonObject receivedMessage) {
        RecordMediaPipeline pipeline = recordPipeline.get(session.getSessionId());
        User user = userRepository.getUser(session.getUsername());
        actionRepository.userEndedRecordingSession(user);

        pipeline.release();
        //todo: notify observers about stopping the live stream
    }

    //endregion

    private void handleLiveVideoWatchRequestEvent(UserSession session, JsonObject receivedMessage) throws IOException {
        UserSession userRecording = registry.getByName("voicu.cristian");
        RecordMediaPipeline recordMediaPipeline = recordPipeline.get(userRecording.getSessionId());

        recordMediaPipeline.addLiveWatcher(session);
        session.setRecordMediaPipeline(recordMediaPipeline);

        // sdp negotiation
        String sdpOffer = receivedMessage.get("sdpOffer").getAsString();
        String sdpAnswer = recordMediaPipeline.getWebRtcEpOfSubscriber(session).processOffer(sdpOffer);

        recordMediaPipeline.getWebRtcEpOfSubscriber(session).addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {
            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("method", "media");
                response.addProperty("event", "iceCandidate");
                JsonObject candidate = new JsonObject();
                candidate.addProperty("for", SEND_ICE_FOR_LIVE);
                candidate.add("candidate", JsonUtils
                        .toJsonObject(event.getCandidate()));
                response.add("candidate", candidate);
                try {
                    synchronized (session) {
                        session.sendMessage(response);
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });

        JsonObject response = new JsonObject();
        response.addProperty("method", "media");
        response.addProperty("event", "liveWatchResponse");
        response.addProperty("response", "accepted");
        response.addProperty("sdpAnswer", sdpAnswer);

        synchronized (session) {
            System.out.println("Sending sdp ans message to " + session.getUsername());
            session.sendMessage(response);
        }

        recordMediaPipeline.getWebRtcEpOfSubscriber(session).gatherCandidates();
    }
    //endregion

    /***/
    private void handleSubscribeMethodMessage(UserSession session, JsonObject receivedMessage){
        switch (receivedMessage.get("event").getAsString()){
            case "userUpdated":
                subscriberController.addUserListListener(session);
            default:

        }
    }

    private void handleUnsubscribeMethodMessage(UserSession session, JsonObject receivedMessage){
        switch (receivedMessage.get("event").getAsString()){
            case "userList":
                subscriberController.removeUserListListener(session);
        }
    }

    private void handleErrorResponse(Throwable throwable, final WebSocketSession session, String responseId)
            throws IOException {
//        stop(session);
        log.error(throwable.getMessage(), throwable);
        System.out.println("Error!: " + throwable.getMessage());
        JsonObject response = new JsonObject();

        response.addProperty("id", responseId);
        response.addProperty("response", "rejected");
        response.addProperty("message", throwable.getMessage());

        session.sendMessage(new TextMessage(response.toString()));
    }

    /**
     * This method is used after connection is closed
     */
    private void stop(final WebSocketSession session) throws IOException {
        UserSession stopperUser = registry.getBySession(session);
        if (stopperUser != null) {
            stopperUser.release();
        }
    }


}