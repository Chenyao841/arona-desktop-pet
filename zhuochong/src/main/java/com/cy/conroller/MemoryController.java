package com.cy.conroller;

import com.cy.mapper.PetMemoryMapper;
import com.cy.pojo.PetMemory;
import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 桌宠长期记忆库（pet_memory）管理接口 —— 管理页「桌宠记忆库」面板使用。
 * 记忆会被全量注入对话 system prompt，所以这里提供查看/新增/编辑/删除/清空。
 */
@RestController
@RequestMapping("/ai/memory")
public class MemoryController {

    @Autowired
    private PetMemoryMapper petMemoryMapper;

    private static final String DEFAULT_CHARACTER = "阿罗娜";

    /** 全部记忆（按 id 升序） */
    @GetMapping("/list")
    public Result list() {
        return Result.success(petMemoryMapper.findAll());
    }

    /** 新增一条记忆：{character_name, content}（角色缺省为阿罗娜） */
    @PostMapping("/add")
    public Result add(@RequestBody PetMemory body) {
        String content = body.getContent() == null ? "" : body.getContent().trim();
        if (content.isEmpty()) return Result.error("记忆内容不能为空");
        PetMemory m = new PetMemory();
        String name = body.getCharacter_name() == null ? "" : body.getCharacter_name().trim();
        m.setCharacter_name(name.isEmpty() ? DEFAULT_CHARACTER : name);
        m.setContent(content);
        petMemoryMapper.insert(m);
        return Result.success(m);
    }

    /** 修改记忆内容：{id, content}（角色的修改不在此接口，避免与提炼结果错位） */
    @PutMapping("/update")
    public Result update(@RequestBody PetMemory body) {
        if (body.getId() == null) return Result.error("缺少 id");
        String content = body.getContent() == null ? "" : body.getContent().trim();
        if (content.isEmpty()) return Result.error("记忆内容不能为空");
        PetMemory m = new PetMemory();
        m.setId(body.getId());
        m.setContent(content);
        petMemoryMapper.update(m);
        return Result.success(m);
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Integer id) {
        petMemoryMapper.delete(id);
        return Result.ok("已删除");
    }

    /** 清空：带 character_name 只清该角色，不带（或为空）则清空全部 */
    @PostMapping("/clear")
    public Result clear(@RequestBody(required = false) Map<String, String> req) {
        String name = req == null ? null : req.get("character_name");
        int n = (name == null || name.trim().isEmpty())
                ? petMemoryMapper.deleteEverything()
                : petMemoryMapper.deleteAll(name.trim());
        return Result.ok("已清空 " + n + " 条记忆");
    }
}
