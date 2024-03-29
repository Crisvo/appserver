/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package ro.lic.server.websocket.utils.pipeline;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;


/**
 * Media Pipeline (connection of Media Elements) for playing the recorded one to one video
 * communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 6.1.1
 */
public class PlayMediaPipeline {

    private static final Logger log = LoggerFactory.getLogger(PlayMediaPipeline.class);
    private boolean isStreamEnded = false;

    private final MediaPipeline pipeline;
    private WebRtcEndpoint webRtc;
    private final PlayerEndpoint player;

    public PlayMediaPipeline(KurentoClient kurento, final String path, final WebSocketSession session) {
        // Media pipeline
        pipeline = kurento.createMediaPipeline();

        // Media Elements (WebRtcEndpoint, PlayerEndpoint)
        webRtc = new WebRtcEndpoint.Builder(pipeline).build();
        webRtc.setMaxVideoRecvBandwidth(3000, new Continuation<Void>() {
            @Override
            public void onSuccess(Void aVoid) throws Exception {
                System.out.println("Set max video recv band");
            }

            @Override
            public void onError(Throwable throwable) throws Exception {
                System.out.println("Failed to set max video recv band");
            }
        }); // unit kbps (set it to 2.5 mbs)
        webRtc.setMaxVideoSendBandwidth(3000, new Continuation<Void>() {
            @Override
            public void onSuccess(Void aVoid) throws Exception {
                System.out.println("Set max video recv band");
            }

            @Override
            public void onError(Throwable throwable) throws Exception {
                System.out.println("Failed to set max video recv band");
            }
        });

        webRtc.setMaxOutputBitrate(3000);
        webRtc.setMinOutputBitrate(3000);

        //player = new PlayerEndpoint.Builder(pipeline, RECORDING_PATH + user + RECORDING_EXT).build();
        player = new PlayerEndpoint.Builder(pipeline, path).build();

        // Connection
        player.connect(webRtc);
    }

    public void sendPlayEnd(WebSocketSession session) {
        try {
            isStreamEnded = true;
            JsonObject response = new JsonObject();
            response.addProperty("method", "media");
            response.addProperty("event", "playbackEnd");

            synchronized (session) {
                session.sendMessage(new TextMessage(response.toString()));
            }
        } catch (IOException e) {
            log.error("Error sending playEndOfStream message", e);
        }

        // Release pipeline
        pipeline.release();
        this.webRtc = null;
    }

    public void play() {
        player.play();
    }

    public void addIceCandidate(IceCandidate iceCandidate) {
        webRtc.addIceCandidate(iceCandidate, new Continuation<Void>() {
            @Override
            public void onSuccess(Void aVoid) throws Exception {
                System.out.println("Candidate added successfully!");
            }

            @Override
            public void onError(Throwable throwable) throws Exception {
                System.out.println("Error at adding candidates...");
            }
        });
    }

    public String generateSdpAnswer(String sdpOffer) {
        return webRtc.processOffer(sdpOffer);
    }

    public MediaPipeline getPipeline() {
        return pipeline;
    }

    public WebRtcEndpoint getWebRtc() {
        return webRtc;
    }

    public PlayerEndpoint getPlayer() {
        return player;
    }

    public boolean isStreamEnded() {
        return isStreamEnded;
    }
}
