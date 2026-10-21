package com.cy.qq.game;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 你画我猜（QQ 版）的持久层：三张表首次运行时自建。
 *
 * <p>词库不在这里：直接复用桌宠那张 {@code pictionary_word}（见 {@link QqPicWordBank}），
 * 一份词库两处用，避免两边题目打架。
 */
@Mapper
public interface QqPicMapper {

    // ===== 建表 =====

    @Update("CREATE TABLE IF NOT EXISTS qq_pic_game (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "chat_type VARCHAR(8) NOT NULL," +
            "chat_id BIGINT NOT NULL," +
            "owner_qq BIGINT NOT NULL," +
            "owner_nick VARCHAR(64) DEFAULT ''," +
            "word_id INT DEFAULT 0," +
            "word VARCHAR(32) NOT NULL," +
            "aliases VARCHAR(128) DEFAULT ''," +
            "category VARCHAR(32) DEFAULT ''," +
            "difficulty TINYINT DEFAULT 1," +
            "state VARCHAR(16) NOT NULL DEFAULT 'playing'," +
            "round_count INT DEFAULT 0," +
            "token VARCHAR(48) NOT NULL," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "ended_at TIMESTAMP NULL," +
            "KEY idx_chat (chat_type, chat_id, state)," +
            "UNIQUE KEY uk_token (token)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createGameTable();

    @Update("CREATE TABLE IF NOT EXISTS qq_pic_guess (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "game_id BIGINT NOT NULL," +
            "round_no INT NOT NULL," +
            "guess VARCHAR(64) DEFAULT ''," +
            "correct TINYINT DEFAULT 0," +
            "changes_desc VARCHAR(128) DEFAULT ''," +
            "say VARCHAR(128) DEFAULT ''," +
            "shot_path VARCHAR(255) DEFAULT ''," +
            "tokens INT DEFAULT 0," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "KEY idx_game (game_id, round_no)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createGuessTable();

    @Update("CREATE TABLE IF NOT EXISTS qq_pic_allow (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "kind VARCHAR(8) NOT NULL," +
            "target_id BIGINT NOT NULL," +
            "note VARCHAR(64) DEFAULT ''," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_kind_target (kind, target_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createAllowTable();

    // ===== 对局 =====

    @Insert("INSERT INTO qq_pic_game (chat_type, chat_id, owner_qq, owner_nick, word_id, word, aliases," +
            " category, difficulty, state, round_count, token) VALUES (#{chat_type}, #{chat_id}, #{owner_qq}," +
            " #{owner_nick}, #{word_id}, #{word}, #{aliases}, #{category}, #{difficulty}, 'playing', 0, #{token})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGame(QqPicGameRow g);

    @Select("SELECT * FROM qq_pic_game WHERE id = #{id}")
    QqPicGameRow findGame(@Param("id") long id);

    @Select("SELECT * FROM qq_pic_game WHERE token = #{token} LIMIT 1")
    QqPicGameRow findByToken(@Param("token") String token);

    /** 该会话当前进行中的那一局（一个群同时只允许一局） */
    @Select("SELECT * FROM qq_pic_game WHERE chat_type = #{chatType} AND chat_id = #{chatId}" +
            " AND state = 'playing' ORDER BY id DESC LIMIT 1")
    QqPicGameRow findPlaying(@Param("chatType") String chatType, @Param("chatId") long chatId);

    @Select("SELECT * FROM qq_pic_game WHERE state = 'playing' AND created_at < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    List<QqPicGameRow> findExpired(@Param("minutes") int minutes);

    @Update("UPDATE qq_pic_game SET state = #{state}, round_count = #{rounds}, ended_at = NOW() WHERE id = #{id}")
    int endGame(@Param("id") long id, @Param("state") String state, @Param("rounds") int rounds);

    @Update("UPDATE qq_pic_game SET round_count = round_count + 1 WHERE id = #{id}")
    int incRound(@Param("id") long id);

    // ===== 猜测记录 =====

    @Insert("INSERT INTO qq_pic_guess (game_id, round_no, guess, correct, changes_desc, say, shot_path, tokens)" +
            " VALUES (#{game_id}, #{round_no}, #{guess}, #{correct}, #{changes_desc}, #{say}, #{shot_path}, #{tokens})")
    int insertGuess(QqPicGuessRow r);

    @Select("SELECT * FROM qq_pic_guess WHERE game_id = #{gameId} ORDER BY round_no")
    List<QqPicGuessRow> findGuesses(@Param("gameId") long gameId);

    @Select("SELECT COUNT(*) FROM qq_pic_guess WHERE game_id = #{gameId}")
    int countGuesses(@Param("gameId") long gameId);

    // ===== 白名单（哪些群可玩 / 哪些 QQ 能开局）=====

    @Insert("INSERT IGNORE INTO qq_pic_allow (kind, target_id, note) VALUES (#{kind}, #{targetId}, #{note})")
    int addAllow(@Param("kind") String kind, @Param("targetId") long targetId, @Param("note") String note);

    @Delete("DELETE FROM qq_pic_allow WHERE kind = #{kind} AND target_id = #{targetId}")
    int delAllow(@Param("kind") String kind, @Param("targetId") long targetId);

    @Select("SELECT target_id AS targetId, note, created_at AS createdAt FROM qq_pic_allow" +
            " WHERE kind = #{kind} ORDER BY target_id")
    List<Map<String, Object>> listAllow(@Param("kind") String kind);

    @Select("SELECT COUNT(*) FROM qq_pic_allow WHERE kind = #{kind} AND target_id = #{targetId}")
    int isAllowed(@Param("kind") String kind, @Param("targetId") long targetId);

    /** 战绩（管理指令 /qq_pic/stats 用） */
    @Select("SELECT state, COUNT(*) AS n FROM qq_pic_game GROUP BY state")
    List<Map<String, Object>> statsByState();

    // ===== 每群每天不重复出题 =====
    // 规则（老师定的）：同一个群一天之内出过的题不再出，**被"换题"换掉的也算出过**；
    // 全部题目都出过一轮之后，把该群当天的记录清空重新开始。

    @Update("CREATE TABLE IF NOT EXISTS qq_pic_daily_word (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "usage_date DATE NOT NULL," +
            "chat_id BIGINT NOT NULL," +
            "word_id INT NOT NULL," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_day_chat_word (usage_date, chat_id, word_id)," +
            "KEY idx_day_chat (usage_date, chat_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createDailyWordTable();

    @Insert("INSERT IGNORE INTO qq_pic_daily_word (usage_date, chat_id, word_id) VALUES (CURDATE(), #{chatId}, #{wordId})")
    int markWordUsedToday(@Param("chatId") long chatId, @Param("wordId") int wordId);

    @Select("SELECT word_id FROM qq_pic_daily_word WHERE usage_date = CURDATE() AND chat_id = #{chatId}")
    List<Integer> todayUsedWordIds(@Param("chatId") long chatId);

    @Select("SELECT COUNT(*) FROM qq_pic_daily_word WHERE usage_date = CURDATE() AND chat_id = #{chatId}")
    int countTodayUsedWords(@Param("chatId") long chatId);

    @Delete("DELETE FROM qq_pic_daily_word WHERE usage_date = CURDATE() AND chat_id = #{chatId}")
    int resetTodayWords(@Param("chatId") long chatId);

    // ===== 词库（跨表查 pictionary_word，不复制一份词库）=====

    @Select("SELECT * FROM pictionary_word WHERE id = #{id}")
    com.cy.pojo.PictionaryWord findWord(@Param("id") int id);

    @Select("SELECT COUNT(*) FROM pictionary_word WHERE enabled = 1")
    int countEnabledWords();

    @Select("SELECT COUNT(*) FROM pictionary_word WHERE enabled = 1 AND difficulty = #{d}")
    int countEnabledWordsByDifficulty(@Param("d") int d);

    /** 出题次数 +1（让冷门词也有机会被抽到） */
    @Update("UPDATE pictionary_word SET used_count = used_count + 1, last_used = NOW() WHERE id = #{id}")
    int bumpUsed(@Param("id") int id);

    /** 按难度挑一个**本群今天没出过**的词；exclude 为空时就是普通随机 */
    @Select("<script>SELECT * FROM pictionary_word WHERE enabled = 1 AND difficulty = #{d} " +
            "<if test='exclude != null and exclude.size() > 0'>AND id NOT IN " +
            "<foreach item='x' collection='exclude' open='(' separator=',' close=')'>#{x}</foreach></if> " +
            "ORDER BY used_count ASC, RAND() LIMIT 1</script>")
    com.cy.pojo.PictionaryWord pickWordNotIn(@Param("d") int d, @Param("exclude") java.util.Set<Integer> exclude);

    /** 不限难度地挑一个今天没出过的（该难度已出完时的兜底） */
    @Select("<script>SELECT * FROM pictionary_word WHERE enabled = 1 " +
            "<if test='exclude != null and exclude.size() > 0'>AND id NOT IN " +
            "<foreach item='x' collection='exclude' open='(' separator=',' close=')'>#{x}</foreach></if> " +
            "ORDER BY used_count ASC, RAND() LIMIT 1</script>")
    com.cy.pojo.PictionaryWord pickAnyWordNotIn(@Param("exclude") java.util.Set<Integer> exclude);

    /** 换题时清掉这局的猜测历史（旧题的猜测对新题没有参考价值） */
    @Delete("DELETE FROM qq_pic_guess WHERE game_id = #{gameId}")
    int clearGuesses(@Param("gameId") long gameId);

    @Update("UPDATE qq_pic_game SET word_id = #{w.word_id}, word = #{w.word}, aliases = #{w.aliases}," +
            " category = #{w.category}, difficulty = #{w.difficulty}, round_count = 0 WHERE id = #{id}")
    int replaceWord(@Param("id") long id, @Param("w") QqPicGameRow w);
}
