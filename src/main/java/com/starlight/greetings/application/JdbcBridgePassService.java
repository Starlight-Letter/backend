package com.starlight.greetings.application;
import com.starlight.greetings.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class JdbcBridgePassService implements BridgePassService {
 /** 현장에서 쓰고 떠나는 경험이라 집에 가서까지 이어지지 않도록 짧게 둔다 */
 public static final Duration PASS_TTL = Duration.ofHours(3);
 private static final SecureRandom RANDOM = new SecureRandom();
 private final JdbcTemplate jdbc; public JdbcBridgePassService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional public Map<String,Object> issue(String bridgeId,String qrKey,String previousToken){
  Boolean valid=qrKey!=null&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from bridges where id=? and active=true and qr_key=?)",Boolean.class,bridgeId,qrKey));
  if(!valid)throw new ApiException(HttpStatus.FORBIDDEN,"QR_INVALID","확인할 수 없는 QR이에요. 다리 근처 포스터의 QR로 다시 들어와 주세요.");
  // 마지막으로 찍은 다리 하나만 남긴다
  if(previousToken!=null)jdbc.update("delete from bridge_passes where token_hash=?",digest(previousToken));
  jdbc.update("delete from bridge_passes where expires_at<=now()");
  byte[] raw=new byte[32];RANDOM.nextBytes(raw);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  Instant expiresAt=Instant.now().plus(PASS_TTL);
  jdbc.update("insert into bridge_passes (token_hash,bridge_id,expires_at) values (?,?,?)",digest(token),bridgeId,Timestamp.from(expiresAt));
  Map<String,Object> pass=new LinkedHashMap<>();pass.put("token",token);pass.put("bridgeId",bridgeId);pass.put("expiresAt",expiresAt.toString());return pass;
 }
 public Map<String,Object> current(String token){
  if(token==null)return null;
  var rows=jdbc.query("select bridge_id,expires_at from bridge_passes where token_hash=? and expires_at>now()",(r,i)->{Map<String,Object> pass=new LinkedHashMap<>();pass.put("bridgeId",r.getString(1));pass.put("expiresAt",r.getTimestamp(2).toInstant().toString());return pass;},digest(token));
  return rows.isEmpty()?null:rows.get(0);
 }
 public void requireWrite(String token,String bridgeId){
  var pass=current(token);
  if(pass==null||!bridgeId.equals(pass.get("bridgeId")))throw new ApiException(HttpStatus.FORBIDDEN,"BRIDGE_LOCKED","이 다리의 편지는 다리 근처 포스터의 QR로 들어와야 띄울 수 있어요.");
 }
 public List<Map<String,Object>> qrKeys(){
  return jdbc.query("select id,name,qr_key from bridges where active=true order by display_order",(r,i)->{Map<String,Object> item=new LinkedHashMap<>();item.put("bridgeId",r.getString(1));item.put("bridgeName",r.getString(2));item.put("qrKey",r.getString(3));return item;});
 }
 @Transactional public Map<String,Object> rotate(String bridgeId,String reason,String actor){
  if(reason==null||reason.trim().length()<2)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_REASON","처리 사유를 입력해 주세요.");
  String key=UUID.randomUUID().toString().replace("-","");
  if(jdbc.update("update bridges set qr_key=? where id=? and active=true",key,bridgeId)==0)throw new ApiException(HttpStatus.NOT_FOUND,"BRIDGE_NOT_FOUND","다리를 찾을 수 없습니다.");
  jdbc.update("delete from bridge_passes where bridge_id=?",bridgeId);
  jdbc.update("insert into admin_actions(id,actor_user_id,action,target_id,reason) values(?::uuid,?::uuid,?,?,?)",UUID.randomUUID().toString(),actor,"rotate_qr_key",bridgeId,reason.trim());
  return Map.of("bridgeId",bridgeId,"qrKey",key);
 }
 private String digest(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
