package com.cy.mapper;

import com.cy.pojo.Conservation;
import com.cy.pojo.ConversationChoice;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ConservationMapper {

    @Select("<script>SELECT * FROM conversation WHERE 1=1 <if test='userId != null and userId != \"\"'> AND (user_id = #{userId} OR user_id = '-1')</if></script>")
    List<Conservation> findByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM conversation WHERE id = #{id}")
    Conservation findById(Integer id);

    @Insert("INSERT INTO conversation (user_id, user_text, boat_text, motion, choice_id, user_operation, role, sound_effect, special_effect) " +
            "VALUES (#{user_id}, #{user_text}, #{boat_text}, #{motion}, #{choice_id}, #{user_operation}, #{role}, #{sound_effect}, #{special_effect})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Conservation c);

    @Update("UPDATE conversation SET user_text=#{user_text}, boat_text=#{boat_text}, " +
            "motion=#{motion}, user_operation=#{user_operation}, " +
            "sound_effect=#{sound_effect}, special_effect=#{special_effect} WHERE id=#{id}")
    int update(Conservation c);

    @Update("UPDATE conversation SET choice_id = #{choiceId} WHERE id = #{id}")
    int updateChoiceId(@Param("id") Integer id, @Param("choiceId") Integer choiceId);

    @Delete("DELETE FROM conversation WHERE id = #{id}")
    int deleteById(Integer id);

    @Select("<script>" +
            "SELECT id, boat_text, motion, choice_id, sound_effect, special_effect FROM conversation WHERE role = 'basic'" +
            "<if test='user_operation != null and user_operation != \"\"'> AND user_operation = #{user_operation}</if>" +
            "<if test='user_text != null and user_text != \"\"'> AND user_text = #{user_text}</if>" +
            " ORDER BY RAND() LIMIT 1" +
            "</script>")
    Conservation findReaction(@Param("user_operation") String user_operation,
                              @Param("user_text") String user_text);

    // 交互操作（rub/milk 等）也可配置在选项表：按 user_operation 查一条有文本的选项
    @Select("SELECT id, choice_id as choiceId, user_operation as userOperation, choice_text as choiceText, " +
            "choice_action as choiceAction, boat_text as boatText, motion, next_choice_id as nextChoiceId, visible, " +
            "sound_effect as soundEffect, special_effect as specialEffect " +
            "FROM conversation_choice WHERE user_operation = #{userOperation} AND boat_text IS NOT NULL AND boat_text != '' " +
            "ORDER BY RAND() LIMIT 1")
    ConversationChoice findChoiceByUserOperation(@Param("userOperation") String userOperation);

    @Select("SELECT DISTINCT motion FROM conversation WHERE motion IS NOT NULL AND motion != ''")
    List<String> findAllMotions();

    @Select("SELECT DISTINCT motion FROM conversation WHERE motion LIKE 'aluona\\_%'")
    List<String> findAluonaMotions();

    @Select("SELECT id, boat_text, motion, choice_id, sound_effect, special_effect FROM conversation WHERE role = 'basic' AND user_operation = 'idle' ORDER BY RAND() LIMIT 1")
    Conservation findIdle();

    @Select("SELECT id, user_operation, user_text, boat_text, motion FROM conversation WHERE role = 'basic' AND boat_text IS NOT NULL AND boat_text != '' ORDER BY id")
    List<Conservation> findBasicResponses();

    @Select("SELECT id, user_operation as userOperation, choice_text as choiceText, boat_text as boatText, motion FROM conversation_choice WHERE user_operation IS NOT NULL AND user_operation != 'chat' AND boat_text IS NOT NULL AND boat_text != '' ORDER BY id")
    List<ConversationChoice> findBasicChoices();

    @Select("SELECT id, choice_id as choiceId, user_operation as userOperation, choice_text as choiceText, " +
            "choice_action as choiceAction, boat_text as boatText, motion, next_choice_id as nextChoiceId, visible, " +
            "sound_effect as soundEffect, special_effect as specialEffect " +
            "FROM conversation_choice WHERE choice_id = #{choiceId} ORDER BY id")
    List<ConversationChoice> findChoicesByChoiceId(@Param("choiceId") Integer choiceId);

    @Insert("INSERT INTO conversation_choice (choice_id, user_operation, choice_text, choice_action, boat_text, motion, next_choice_id, visible, sound_effect, special_effect) " +
            "VALUES (#{choiceId}, #{userOperation}, #{choiceText}, #{choiceAction}, #{boatText}, #{motion}, #{nextChoiceId}, #{visible}, #{soundEffect}, #{specialEffect})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertChoice(ConversationChoice choice);

    @Delete("DELETE FROM conversation_choice WHERE id = #{id}")
    int deleteChoiceById(Integer id);

    @Update("UPDATE conversation_choice SET choice_text=#{choiceText}, choice_action=#{choiceAction}, " +
            "boat_text=#{boatText}, motion=#{motion}, user_operation=#{userOperation}, visible=#{visible}, " +
            "sound_effect=#{soundEffect}, special_effect=#{specialEffect} WHERE id=#{id}")
    int updateChoice(ConversationChoice choice);

    @Select("SELECT id, choice_id as choiceId, user_operation as userOperation, choice_text as choiceText, " +
            "choice_action as choiceAction, boat_text as boatText, motion, next_choice_id as nextChoiceId, visible, " +
            "sound_effect as soundEffect, special_effect as specialEffect " +
            "FROM conversation_choice WHERE id = #{id}")
    ConversationChoice findChoiceById(@Param("id") Integer id);

    @Select("SELECT COALESCE(MAX(choice_id), 0) FROM conversation_choice")
    int getMaxChoiceId();

    @Update("UPDATE conversation_choice SET next_choice_id = #{nextId} WHERE id = #{id}")
    int updateNextChoiceId(@Param("id") Integer id, @Param("nextId") Integer nextId);

    @Update("SET @row = 0; UPDATE conversation SET id = (@row := @row + 1) ORDER BY id")
    void reorderIds();

    @Update("ALTER TABLE conversation AUTO_INCREMENT = 1")
    void resetAutoIncrement();

    @Update("SET @row = 0; UPDATE conversation_choice SET id = (@row := @row + 1) ORDER BY id")
    void reorderChoiceIds();

    @Update("ALTER TABLE conversation_choice AUTO_INCREMENT = 1")
    void resetChoiceAutoIncrement();

    @Select("SELECT id, boat_text, motion, choice_id, sound_effect, special_effect FROM conversation WHERE role = 'basic' AND user_text LIKE CONCAT('%',#{userText},'%') ORDER BY RAND() LIMIT 1")
    Conservation findTalkReaction(@Param("userText") String userText);
}
