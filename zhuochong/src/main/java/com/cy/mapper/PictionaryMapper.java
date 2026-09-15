package com.cy.mapper;

import com.cy.pojo.PictionaryGame;
import com.cy.pojo.PictionaryGuess;
import com.cy.pojo.PictionaryWord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 你画我猜：词库 / 对局 / 每轮猜测。
 * 三张表首次运行时自建；词库为空时由 PictionaryService 灌入 100 个基础词（按难度分级）。
 */
@Mapper
public interface PictionaryMapper {

    // ===== 建表 =====
    @Update("CREATE TABLE IF NOT EXISTS pictionary_word (" +
            "id INT PRIMARY KEY AUTO_INCREMENT," +
            "word VARCHAR(40) NOT NULL," +
            "aliases VARCHAR(200) DEFAULT ''," +
            "category VARCHAR(20) DEFAULT ''," +
            "difficulty TINYINT DEFAULT 1," +
            "enabled TINYINT DEFAULT 1," +
            "used_count INT DEFAULT 0," +
            "last_used DATETIME NULL," +
            "UNIQUE KEY uk_pw_word (word)" +
            ")")
    void createWordTable();

    @Update("CREATE TABLE IF NOT EXISTS pictionary_game (" +
            "id INT PRIMARY KEY AUTO_INCREMENT," +
            "word VARCHAR(40) NOT NULL," +
            "aliases VARCHAR(200) DEFAULT ''," +
            "category VARCHAR(20) DEFAULT ''," +
            "difficulty TINYINT DEFAULT 1," +
            "started_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "ended_at DATETIME NULL," +
            "result VARCHAR(16) DEFAULT NULL," +
            "guess_count INT DEFAULT 0," +
            "duration_sec INT DEFAULT 0" +
            ")")
    void createGameTable();

    @Update("CREATE TABLE IF NOT EXISTS pictionary_guess (" +
            "id INT PRIMARY KEY AUTO_INCREMENT," +
            "game_id INT NOT NULL," +
            "round_no INT NOT NULL," +
            "guess VARCHAR(60) DEFAULT ''," +
            "say VARCHAR(200) DEFAULT ''," +
            "changes_desc VARCHAR(200) DEFAULT ''," +
            "correct TINYINT DEFAULT 0," +
            "shot VARCHAR(120) DEFAULT ''," +
            "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "INDEX idx_pg_game (game_id)" +
            ")")
    void createGuessTable();

    // ===== 词库 =====
    @Select("SELECT COUNT(*) FROM pictionary_word")
    int countWords();

    @Insert("INSERT INTO pictionary_word (word, aliases, category, difficulty) " +
            "VALUES (#{word}, #{aliases}, #{category}, #{difficulty})")
    int insertWord(PictionaryWord w);

    @Select("SELECT * FROM pictionary_word ORDER BY difficulty, category, id")
    List<PictionaryWord> findAllWords();

    @Select("SELECT * FROM pictionary_word WHERE enabled = 1 AND (#{d} = 0 OR difficulty = #{d}) " +
            "ORDER BY used_count ASC, RAND() LIMIT 1")
    PictionaryWord pickWord(@Param("d") int difficulty);

    @Update("UPDATE pictionary_word SET used_count = used_count + 1, last_used = NOW() WHERE id = #{id}")
    int bumpUsed(@Param("id") int id);

    @Update("UPDATE pictionary_word SET word=#{word}, aliases=#{aliases}, category=#{category}, " +
            "difficulty=#{difficulty}, enabled=#{enabled} WHERE id=#{id}")
    int updateWord(PictionaryWord w);

    @Delete("DELETE FROM pictionary_word WHERE id = #{id}")
    int deleteWord(@Param("id") int id);

    // ===== 对局 =====
    @Insert("INSERT INTO pictionary_game (word, aliases, category, difficulty) " +
            "VALUES (#{word}, #{aliases}, #{category}, #{difficulty})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGame(PictionaryGame g);

    @Select("SELECT * FROM pictionary_game WHERE id = #{id}")
    PictionaryGame findGame(@Param("id") int id);

    @Select("SELECT * FROM pictionary_game WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1")
    PictionaryGame findOpenGame();

    @Update("UPDATE pictionary_game SET ended_at = NOW(), result = #{result}, guess_count = #{guessCount}, " +
            "duration_sec = TIMESTAMPDIFF(SECOND, started_at, NOW()) WHERE id = #{id} AND ended_at IS NULL")
    int endGame(@Param("id") int id, @Param("result") String result, @Param("guessCount") int guessCount);

    @Update("UPDATE pictionary_game SET ended_at = NOW(), result = 'quit', " +
            "duration_sec = TIMESTAMPDIFF(SECOND, started_at, NOW()) WHERE ended_at IS NULL")
    int abandonOpenGames();

    @Update("UPDATE pictionary_game SET guess_count = guess_count + 1 WHERE id = #{id}")
    int incGuess(@Param("id") int id);

    @Select("SELECT * FROM pictionary_game WHERE ended_at IS NOT NULL ORDER BY id DESC LIMIT #{n}")
    List<PictionaryGame> findHistory(@Param("n") int n);

    @Select("SELECT COUNT(*) FROM pictionary_game WHERE ended_at IS NOT NULL")
    int countFinished();

    @Select("SELECT COUNT(*) FROM pictionary_game WHERE result = 'win'")
    int countWin();

    @Select("SELECT IFNULL(AVG(guess_count), 0) FROM pictionary_game WHERE result = 'win'")
    double avgWinRounds();

    // ===== 每轮猜测（桌宠的游戏记忆） =====
    @Insert("INSERT INTO pictionary_guess (game_id, round_no, guess, say, changes_desc, correct, shot) " +
            "VALUES (#{game_id}, #{round_no}, #{guess}, #{say}, #{changes_desc}, #{correct}, #{shot})")
    int insertGuess(PictionaryGuess g);

    @Select("SELECT * FROM pictionary_guess WHERE game_id = #{id} ORDER BY round_no")
    List<PictionaryGuess> findGuesses(@Param("id") int id);
}
