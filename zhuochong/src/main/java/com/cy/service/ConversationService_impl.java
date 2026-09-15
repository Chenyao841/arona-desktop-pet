package com.cy.service;

import com.cy.mapper.ConservationMapper;
import com.cy.pojo.Conservation;
import com.cy.pojo.ConversationChoice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ConversationService_impl implements ConversationService {

    @Autowired
    private ConservationMapper mapper;

    @Override
    public List<Conservation> findByUserId(String userId) {
        List<Conservation> all = mapper.findByUserId(userId);
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        return all;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addConversation(Conservation c) {
        if (c.getRole() == null) {
            c.setRole("basic");
        }
        mapper.insert(c);
        mapper.reorderIds();
        mapper.resetAutoIncrement();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Integer id) {
        Conservation conv = mapper.findById(id);
        if (conv != null && conv.getChoice_id() != null && conv.getChoice_id() > 0) {
            deleteChoicesByChoiceId(conv.getChoice_id());
        }
        mapper.deleteById(id);
        mapper.reorderIds();
        mapper.resetAutoIncrement();
    }

    @Override
    public void updateConversation(Conservation c) {
        mapper.update(c);
    }

    @Override
    public Conservation findReaction(String userOperation, String userText) {
        return mapper.findReaction(userOperation, userText);
    }

    @Override
    public ConversationChoice findChoiceByUserOperation(String userOperation) {
        return mapper.findChoiceByUserOperation(userOperation);
    }

    @Override
    public Conservation findIdle() {
        return mapper.findIdle();
    }

    @Override
    public List<Conservation> findBasicResponses() {
        return mapper.findBasicResponses();
    }

    @Override
    public List<ConversationChoice> findBasicChoices() {
        return mapper.findBasicChoices();
    }

    @Override
    public Conservation findTalkReaction(String userText) {
        return mapper.findTalkReaction(userText);
    }

    @Override
    public List<ConversationChoice> findChoicesByChoiceId(Integer choiceId) {
        if (choiceId == null || choiceId <= 0) {
            return Collections.emptyList();
        }
        return mapper.findChoicesByChoiceId(choiceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationChoice addChoice(Integer conversationId, ConversationChoice choice) {
        Conservation conv = mapper.findById(conversationId);
        if (conv == null) return choice;

        Integer cid = conv.getChoice_id();
        if (cid == null || cid <= 0) {
            cid = mapper.getMaxChoiceId() + 1;
            mapper.updateChoiceId(conversationId, cid);
        }

        if (choice.getUserOperation() == null) {
            choice.setUserOperation("chat");
        }
        choice.setChoiceId(cid);
        choice.setNextChoiceId(null);
        mapper.insertChoice(choice);
        mapper.reorderChoiceIds();
        mapper.resetChoiceAutoIncrement();
        return choice;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChoiceById(Integer id) {
        ConversationChoice target = mapper.findChoiceById(id);
        if (target != null && target.getNextChoiceId() != null && target.getNextChoiceId() > 0) {
            deleteChoicesByChoiceId(target.getNextChoiceId());
        }
        mapper.deleteChoiceById(id);
        mapper.reorderChoiceIds();
        mapper.resetChoiceAutoIncrement();
    }

    @Override
    public void updateChoice(ConversationChoice choice) {
        mapper.updateChoice(choice);
    }

    @Override
    public Conservation findById(Integer id) {
        return mapper.findById(id);
    }

    @Override
    public List<String> findAllMotions() {
        return mapper.findAllMotions();
    }

    @Override
    public List<String> findAluonaMotions() {
        return mapper.findAluonaMotions();
    }

    // 递归删除 choice_id 组下所有选项
    private void deleteChoicesByChoiceId(Integer choiceId) {
        if (choiceId == null || choiceId <= 0) return;
        List<ConversationChoice> children = mapper.findChoicesByChoiceId(choiceId);
        if (children == null || children.isEmpty()) return;
        for (ConversationChoice child : children) {
            if (child.getNextChoiceId() != null && child.getNextChoiceId() > 0) {
                deleteChoicesByChoiceId(child.getNextChoiceId());
            }
            mapper.deleteChoiceById(child.getId());
        }
    }

    @Override
    public List<ConversationChoice> findSubChoices(Integer parentChoiceId) {
        ConversationChoice parent = mapper.findChoiceById(parentChoiceId);
        if (parent == null || parent.getNextChoiceId() == null || parent.getNextChoiceId() <= 0) {
            return Collections.emptyList();
        }
        return mapper.findChoicesByChoiceId(parent.getNextChoiceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationChoice addSubChoice(Integer parentChoiceId, ConversationChoice choice) {
        ConversationChoice parent = mapper.findChoiceById(parentChoiceId);
        if (parent == null) return choice;

        // 父已有子选项组 → 复用 choice_id；否则新建组并关联父
        Integer groupId = parent.getNextChoiceId();
        if (groupId == null || groupId <= 0) {
            groupId = mapper.getMaxChoiceId() + 1;
            mapper.updateNextChoiceId(parent.getId(), groupId);
        }

        if (choice.getUserOperation() == null) {
            choice.setUserOperation("chat");
        }
        choice.setChoiceId(groupId);
        choice.setNextChoiceId(null);
        mapper.insertChoice(choice);
        mapper.reorderChoiceIds();
        mapper.resetChoiceAutoIncrement();
        return choice;
    }
}
