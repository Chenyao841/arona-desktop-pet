package com.cy.service;

import com.cy.pojo.Conservation;
import com.cy.pojo.ConversationChoice;

import java.util.List;

public interface ConversationService {
    List<Conservation> findByUserId(String userId);
    void addConversation(Conservation conservation);
    void deleteConversation(Integer id);
    void updateConversation(Conservation conservation);
    Conservation findReaction(String userOperation, String userText);
    ConversationChoice findChoiceByUserOperation(String userOperation);
    Conservation findTalkReaction(String userText);
    Conservation findIdle();
    List<Conservation> findBasicResponses();
    List<ConversationChoice> findBasicChoices();
    List<ConversationChoice> findChoicesByChoiceId(Integer choiceId);
    ConversationChoice addChoice(Integer conversationId, ConversationChoice choice);
    Conservation findById(Integer id);
    List<String> findAllMotions();
    List<String> findAluonaMotions();
    void deleteChoiceById(Integer id);
    void updateChoice(ConversationChoice choice);
    List<ConversationChoice> findSubChoices(Integer parentChoiceId);
    ConversationChoice addSubChoice(Integer parentChoiceId, ConversationChoice choice);
}
