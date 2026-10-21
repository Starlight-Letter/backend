package com.starlight.greetings.application;
import java.util.List;
import java.util.Map;
/** 다리 QR로 들어온 브라우저에만 그 다리의 편지 작성을 열어 준다. QR은 위치 인증이 아니라 현장 포스터를 거쳤다는 표시다. */
public interface BridgePassService {
 /** QR 열쇠가 맞으면 새 허가를 만든다. token은 쿠키에 담을 원문, 나머지는 화면에 보여줄 값. 이전 허가는 버린다. */
 Map<String,Object> issue(String bridgeId, String qrKey, String previousToken);
 /** 살아 있는 허가({bridgeId, expiresAt}) 또는 null */
 Map<String,Object> current(String token);
 /** 이 다리에 쓸 수 있는 허가가 없으면 BRIDGE_LOCKED */
 void requireWrite(String token, String bridgeId);
 /** 운영자 화면용 다리별 QR 열쇠 */
 List<Map<String,Object>> qrKeys();
 /** 포스터가 퍼졌을 때 열쇠를 새로 만들고, 그 다리의 기존 허가를 모두 끊는다 */
 Map<String,Object> rotate(String bridgeId, String reason, String actorUserId);
}
