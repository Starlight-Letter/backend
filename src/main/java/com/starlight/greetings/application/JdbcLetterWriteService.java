package com.starlight.greetings.application;
import com.starlight.greetings.web.ApiException; import com.starlight.greetings.domain.moderation.ContentModerator; import com.starlight.greetings.domain.moderation.ModerationNotice; import java.nio.charset.StandardCharsets; import java.text.Normalizer; import java.security.MessageDigest; import java.util.*; import org.springframework.http.HttpStatus; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service public class JdbcLetterWriteService implements LetterWriteService { private final JdbcTemplate jdbc; private final ContentModerator moderator; private final AchievementService achievements; private final ModerationRules rules; public JdbcLetterWriteService(JdbcTemplate jdbc, ContentModerator moderator, AchievementService achievements, ModerationRules rules){this.jdbc=jdbc;this.moderator=moderator;this.achievements=achievements;this.rules=rules;}
 @Transactional public LetterWriteResult write(Map<String,Object> input){String bridge=(String)input.get("bridgeId"),question=(String)input.get("questionId"),body=(String)input.get("body"),user=(String)input.get("userId"),nickname=(String)input.get("nickname"),key=(String)input.get("idempotencyKey");int theme=input.get("theme") instanceof Number n?n.intValue():0;if(body==null||body.trim().isEmpty()||body.codePointCount(0,body.length())>150)throw bad("INVALID_BODY","편지 내용을 1~150자로 입력해 주세요.");// 걸리면 무엇 때문인지(걸린 표현·범주) 알려 준다
var base=moderator.check(body);if(base!=null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"CONTENT_REVISE",ModerationNotice.of("편지",base.category(),base.matched()));if(key==null||key.length()<8||key.length()>100)throw bad("IDEMPOTENCY_KEY_REQUIRED","제출 요청 키를 확인해 주세요.");if(theme!=1&&theme!=2&&theme!=5&&theme!=6)throw bad("INVALID_THEME","유리병 테마를 다시 선택해 주세요.");if(!achievements.canUseTheme(user,theme))throw bad("THEME_LOCKED","아직 열리지 않은 유리병이에요. 별자리를 모으면 열려요.");Boolean exists=jdbc.queryForObject("select exists(select 1 from bridges where id=? and active=true)",Boolean.class,bridge);if(!Boolean.TRUE.equals(exists))throw new ApiException(HttpStatus.NOT_FOUND,"BRIDGE_NOT_FOUND","다리를 찾을 수 없습니다.");// 운영자 검사 규칙은 캐시에서 본다(규칙이 바뀌면 바로 갱신)
var hit=rules.find(body);if(hit.isPresent())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"CONTENT_REVISE",ModerationNotice.of("편지",hit.get().category(),hit.get().phrase()));    Object anonObj = input.get("anonymous");
    boolean anonymous = Boolean.TRUE.equals(anonObj) || "true".equalsIgnoreCase(String.valueOf(anonObj));
    String displayNickname = anonymous ? "\uC775\uBA85\uC758 \uBCC4\uBE5B" : nickname;
    String hash=digest(bridge+"\n"+question+"\n"+body+"\n"+theme+"\n"+(anonymous ? "anon" : "named"));
    jdbc.update("insert into request_keys (user_id,key,request_hash) values (?::uuid,?,?) on conflict do nothing",user,key,hash);
    var keys=jdbc.query("select request_hash,letter_id::text from request_keys where user_id=?::uuid and key=? for update",(r,i)->new String[]{r.getString(1),r.getString(2)},user,key);
    if(!keys.get(0)[0].equals(hash))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","같은 요청 키로 다른 편지를 제출할 수 없습니다.");
    if(keys.get(0)[1]!=null)return new LetterWriteResult(keys.get(0)[1],true);
    var questions=jdbc.query("select id::text from questions where bridge_id=? and status='published' and effective_at<=now() order by effective_at desc,id desc limit 1",(r,i)->r.getString(1),bridge);
    if(questions.isEmpty()||!questions.get(0).equals(question))throw new ApiException(HttpStatus.CONFLICT,"QUESTION_CHANGED","질문이 바뀌었어요. 새 질문을 확인해 주세요.");
    // 강물 도배 방지: 한 계정이 하루(KST 자정 기준) 한 다리에 3통, 전체 10통까지 (같은 요청 키 재전송은 위에서 이미 돌려보냄)
    // 세기 전에 이 회원 행을 잠가 같은 회원의 작성을 한 줄로 세운다. 안 그러면 동시에 보낸 요청들이 서로의 저장을 못 본 채
    // 모두 "아직 여유 있음"으로 세어 제한을 넘긴다. 다른 회원끼리는 서로 막지 않는다.
    // FOR UPDATE가 아니라 NO KEY UPDATE인 이유: 위 request_keys 삽입이 외래 키 때문에 이 행에 KEY SHARE 잠금을 이미 걸어 두는데,
    // FOR UPDATE는 그것과 충돌해 동시 요청끼리 교착된다. NO KEY UPDATE는 KEY SHARE와 충돌하지 않고 서로끼리만 막는다.
    jdbc.query("select 1 from users where id=?::uuid for no key update",(r,i)->1,user);
    var recent=jdbc.queryForObject("select count(*) filter (where bridge_id=?)::int, count(*)::int from letters where author_user_id=?::uuid and created_at>="+KST_TODAY_START,(r,i)->new int[]{r.getInt(1),r.getInt(2)},bridge,user);
    if(recent[0]>=BRIDGE_DAILY_LIMIT)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"LETTER_LIMIT","오늘은 이 다리에 안부를 충분히 띄웠어요. 내일 다시 띄워 주세요.");
    if(recent[1]>=TOTAL_DAILY_LIMIT)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"LETTER_LIMIT","오늘은 안부를 충분히 띄웠어요. 내일 다시 띄워 주세요.");
    String id=UUID.randomUUID().toString();jdbc.update("insert into letters (id,bridge_id,question_id,author_user_id,display_nickname,body,theme,is_seed,status) values (?::uuid,?,?::uuid,?::uuid,?,?,?,false,'published')",id,bridge,question,user,displayNickname,body.trim(),theme);jdbc.update("update request_keys set letter_id=?::uuid where user_id=?::uuid and key=?",id,user,key);return new LetterWriteResult(id,false);}
 private ApiException bad(String c,String m){return new ApiException(HttpStatus.BAD_REQUEST,c,m);}private String digest(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 static final int BRIDGE_DAILY_LIMIT=3, TOTAL_DAILY_LIMIT=10;
 // 오늘(KST) 0시. 쿼리마다 한 번만 계산되는 값이라 letters_author_recent_idx(author_user_id, created_at) 범위 검색을 그대로 탄다.
 static final String KST_TODAY_START="(date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul')";
 // 남은 횟수와, 다 썼을 때 다음 편지를 쓸 수 있는 시각(다음 KST 자정)
 @Override public Map<String,Object> quota(String user,String bridge){
  return jdbc.queryForObject("select count(*) filter (where bridge_id=?)::int, count(*)::int, "+KST_TODAY_START+" + interval '1 day' from letters where author_user_id=?::uuid and created_at>="+KST_TODAY_START,(r,i)->{
   int bridgeUsed=r.getInt(1), totalUsed=r.getInt(2);
   int bridgeLeft=Math.max(0,BRIDGE_DAILY_LIMIT-bridgeUsed), totalLeft=Math.max(0,TOTAL_DAILY_LIMIT-totalUsed);
   java.time.Instant next=bridgeLeft==0||totalLeft==0?r.getTimestamp(3).toInstant():null;
   Map<String,Object> q=new LinkedHashMap<>();
   q.put("bridgeLimit",BRIDGE_DAILY_LIMIT);q.put("bridgeRemaining",bridgeLeft);q.put("totalLimit",TOTAL_DAILY_LIMIT);q.put("totalRemaining",totalLeft);
   q.put("remaining",Math.min(bridgeLeft,totalLeft));q.put("nextAvailableAt",next==null?null:next.toString());
   return q;
  },bridge,user);
 }
}
