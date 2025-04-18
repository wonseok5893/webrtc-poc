package com.zedd.webrtc.handler;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SignalingHandler extends TextWebSocketHandler {

    // 세션 저장 맵 (세션 ID -> 웹소켓 세션)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // 세션 활성 상태 체크를 위한 스케줄러
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 세션별 마지막 활동 시간 (세션 ID -> 타임스탬프)
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();

    // 연결 타임아웃 기간 (30초)
    private static final long SESSION_TIMEOUT = 30000;

    public SignalingHandler() {
        // 세션 활성 상태 체크 작업 스케줄 (10초마다 실행)
        scheduler.scheduleAtFixedRate(this::checkSessionActivity, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        lastActivityTime.put(sessionId, System.currentTimeMillis());

        log.info("WebSocket 연결 성립: {}", sessionId);

        // 새 사용자에게 현재 접속자 수와 세션 ID 알림
        JSONObject message = new JSONObject();
        message.put("type", "user_count");
        message.put("count", sessions.size());
        message.put("sessionId", sessionId);

        try {
            session.sendMessage(new TextMessage(message.toString()));

            // 다른 모든 사용자에게 새 사용자 알림
            JSONObject newUserMsg = new JSONObject();
            newUserMsg.put("type", "new_user");
            newUserMsg.put("id", sessionId);

            broadcast(newUserMsg.toString(), sessionId);

            // 새 사용자에게 기존 사용자 목록 전송
            for (String existingId : sessions.keySet()) {
                if (!existingId.equals(sessionId)) {
                    JSONObject existingUserMsg = new JSONObject();
                    existingUserMsg.put("type", "new_user");
                    existingUserMsg.put("id", existingId);
                    session.sendMessage(new TextMessage(existingUserMsg.toString()));
                }
            }
        } catch (IOException e) {
            log.error("메시지 전송 오류", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();

        // 세션 활동 시간 업데이트
        lastActivityTime.put(sessionId, System.currentTimeMillis());

        log.debug("메시지 수신: {} from {}", payload, sessionId);

        try {
            JSONObject jsonMessage = new JSONObject(payload);
            String type = jsonMessage.getString("type");

            // 채팅 메시지 처리
            if ("chat".equals(type)) {
                // 발신자 ID 추가
                jsonMessage.put("from", sessionId);

                // 타겟이 있으면 특정 사용자에게만 전송
                if (jsonMessage.has("target")) {
                    String targetId = jsonMessage.getString("target");
                    WebSocketSession targetSession = sessions.get(targetId);

                    if (targetSession != null && targetSession.isOpen()) {
                        targetSession.sendMessage(new TextMessage(jsonMessage.toString()));
                    }
                } else {
                    // 타겟이 없으면 모든 다른 사용자에게 브로드캐스트
                    broadcast(jsonMessage.toString(), sessionId);
                }
                return;
            }

            // 핑 메시지 처리
            if ("ping".equals(type)) {
                JSONObject pongMessage = new JSONObject();
                pongMessage.put("type", "pong");
                session.sendMessage(new TextMessage(pongMessage.toString()));
                return;
            }

            // WebRTC 시그널링 메시지 처리
            if (jsonMessage.has("target")) {
                String targetId = jsonMessage.getString("target");
                WebSocketSession targetSession = sessions.get(targetId);

                if (targetSession != null && targetSession.isOpen()) {
                    // 발신자 ID 추가
                    jsonMessage.put("from", sessionId);
                    targetSession.sendMessage(new TextMessage(jsonMessage.toString()));
                } else {
                    // 대상 세션이 없는 경우 오류 메시지 회신
                    JSONObject errorMsg = new JSONObject();
                    errorMsg.put("type", "error");
                    errorMsg.put("message", "대상 사용자가 연결되어 있지 않습니다.");
                    session.sendMessage(new TextMessage(errorMsg.toString()));
                }
            } else {
                // 타겟이 없으면 모든 다른 세션에 브로드캐스트
                jsonMessage.put("from", sessionId);
                broadcast(jsonMessage.toString(), sessionId);
            }
        } catch (Exception e) {
            log.error("메시지 처리 오류", e);
            try {
                // 클라이언트에 오류 알림
                JSONObject errorMsg = new JSONObject();
                errorMsg.put("type", "error");
                errorMsg.put("message", "메시지 처리 중 오류가 발생했습니다.");
                session.sendMessage(new TextMessage(errorMsg.toString()));
            } catch (IOException ex) {
                log.error("오류 메시지 전송 실패", ex);
            }
        }
    }

    protected void handlePongMessage(WebSocketSession session, PingMessage message) throws Exception {
        // Pong 메시지를 받으면 세션의
        String sessionId = session.getId();
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.debug("Pong 메시지 수신: {}", sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        lastActivityTime.remove(sessionId);

        log.info("WebSocket 연결 종료: {} - 상태: {}", sessionId, status);

        // 다른 모든 사용자에게 사용자 퇴장 알림
        JSONObject message = new JSONObject();
        message.put("type", "user_left");
        message.put("id", sessionId);

        broadcast(message.toString(), null);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = session.getId();
        log.error("WebSocket 전송 오류: {}", sessionId, exception);

        // 에러 발생 시 세션 정리
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException e) {
            log.error("세션 종료 중 오류 발생", e);
        } finally {
            sessions.remove(sessionId);
            lastActivityTime.remove(sessionId);

            // 다른 사용자에게 알림
            JSONObject message = new JSONObject();
            message.put("type", "user_left");
            message.put("id", sessionId);
            broadcast(message.toString(), null);
        }
    }

    /**
     * 모든 세션에 주기적으로 Ping 메시지를 보내 연결 상태 확인
     */
    private void checkSessionActivity() {
        long currentTime = System.currentTimeMillis();
        sessions.forEach((id, session) -> {
            try {
                // 마지막 활동 시간 확인
                Long lastActivity = lastActivityTime.get(id);
                if (lastActivity != null && currentTime - lastActivity > SESSION_TIMEOUT) {
                    log.info("세션 타임아웃: {}", id);
                    session.close(CloseStatus.NORMAL);
                    sessions.remove(id);
                    lastActivityTime.remove(id);

                    // 다른 사용자에게 알림
                    JSONObject message = new JSONObject();
                    message.put("type", "user_left");
                    message.put("id", id);
                    broadcast(message.toString(), null);
                } else if (session.isOpen()) {
                    // Ping 메시지 전송
                    session.sendMessage(new PingMessage(ByteBuffer.wrap("ping".getBytes())));
                }
            } catch (IOException e) {
                log.error("Ping 전송 중 오류: {}", id, e);
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ex) {
                    log.error("세션 종료 중 오류", ex);
                }
                sessions.remove(id);
                lastActivityTime.remove(id);
            }
        });
    }

    private void broadcast(String message, String excludeId) {
        sessions.forEach((id, session) -> {
            // 제외할 ID가 없거나 현재 세션 ID가 제외할 ID와 다른 경우에만 전송
            if ((excludeId == null || !id.equals(excludeId)) && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("브로드캐스트 오류: {}", id, e);
                    // 오류 발생 시 세션 제거 처리
                    try {
                        session.close(CloseStatus.SERVER_ERROR);
                    } catch (IOException ex) {
                        log.error("세션 종료 중 오류", ex);
                    }
                    sessions.remove(id);
                    lastActivityTime.remove(id);
                }
            }
        });
    }

    /**
     * 애플리케이션 종료 시 리소스 정리
     */
    public void destroy() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}