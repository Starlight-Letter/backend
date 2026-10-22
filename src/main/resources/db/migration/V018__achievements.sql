-- 업적(별자리): 한 번 달성하면 기록해 두어, 나중에 위로가 취소되거나 편지를 지워도 켜진 별이 꺼지지 않게 한다.
CREATE TABLE user_achievements (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code text NOT NULL,
  unlocked_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, code)
);

-- 업적으로 여는 유리병: 5 = 별빛 병(세 다리의 안부), 6 = 은하수 병(북두칠성 완성)
ALTER TABLE letters DROP CONSTRAINT letters_theme_check;
ALTER TABLE letters ADD CONSTRAINT letters_theme_check CHECK (theme BETWEEN 1 AND 6);
