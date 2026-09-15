
/*!50503 SET NAMES utf8mb4 */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `adminlist` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `name` varchar(10) NOT NULL COMMENT '姓名（关联userlist）',
  `username` varchar(20) DEFAULT NULL COMMENT '登录用户名（可空）',
  `userkey` varchar(20) NOT NULL DEFAULT '123456' COMMENT '密码',
  `identity` varchar(10) NOT NULL DEFAULT '员工' COMMENT '身份: 用户/员工/管理',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户认证表';
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `api_key` varchar(255) DEFAULT NULL,
  `api_url` varchar(255) DEFAULT 'https://api.deepseek.com/v1/chat/completions',
  `tts_url` varchar(255) DEFAULT NULL,
  `model` varchar(50) DEFAULT 'deepseek-chat',
  `max_tokens` int DEFAULT '2048',
  `temperature` decimal(3,2) DEFAULT '0.90',
  `refer_wav` varchar(500) DEFAULT NULL,
  `prompt_text` varchar(500) DEFAULT NULL,
  `tts_speed` double DEFAULT '1',
  `sample_steps` int DEFAULT '32',
  `gpt_model_path` varchar(500) DEFAULT NULL,
  `sovits_model_path` varchar(500) DEFAULT NULL,
  `live_mode` tinyint DEFAULT '0',
  `bili_cookie` varchar(500) DEFAULT NULL,
  `room_id` int DEFAULT NULL,
  `danmaku_enabled` int NOT NULL DEFAULT '1',
  `netease_uid` varchar(50) DEFAULT NULL,
  `mahjong_enabled` int DEFAULT '0',
  `web_search_enabled` tinyint DEFAULT '0',
  `web_search_api_key` varchar(255) DEFAULT NULL,
  `web_search_count` int DEFAULT '5',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_history` (
  `id` int NOT NULL AUTO_INCREMENT,
  `session_id` varchar(50) NOT NULL,
  `user_text` varchar(500) DEFAULT NULL,
  `user_operation` varchar(100) DEFAULT NULL,
  `boat_text` text,
  `motion` varchar(255) DEFAULT NULL,
  `sound_effect` varchar(255) DEFAULT NULL,
  `special_effect` varchar(200) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `starred` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1615 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_prompt` (
  `id` int NOT NULL AUTO_INCREMENT,
  `prompt_type` varchar(50) NOT NULL,
  `content` text NOT NULL,
  `version` int DEFAULT '1',
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `basicinfo` (
  `id` int NOT NULL AUTO_INCREMENT,
  `姓名` varchar(50) DEFAULT NULL,
  `身份` varchar(50) DEFAULT NULL,
  `表情存放地址` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `command_lib` (
  `id` int NOT NULL AUTO_INCREMENT,
  `module` varchar(40) NOT NULL COMMENT '所属模块',
  `command` varchar(100) NOT NULL COMMENT '指令词',
  `feature` varchar(300) NOT NULL DEFAULT '' COMMENT '功能说明',
  `enabled` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_command` (`command`)
) ENGINE=InnoDB AUTO_INCREMENT=66 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `commentary_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `enabled` tinyint DEFAULT '0',
  `interval_sec` int DEFAULT '12',
  `min_change_score` int DEFAULT '3',
  `max_streak` int DEFAULT '3',
  `silence_sec` int DEFAULT '45',
  `after_talk_sec` int DEFAULT '20',
  `voice_on` tinyint DEFAULT '1',
  `volume_percent` int DEFAULT '45',
  `skip_percent` int DEFAULT '35',
  `max_chars` int DEFAULT '20',
  `keep_minutes` int DEFAULT '60',
  `auto_detect` tinyint DEFAULT '1',
  `manual_ttl_min` int DEFAULT '15',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `commentary_context` (
  `id` int NOT NULL AUTO_INCREMENT,
  `code` varchar(20) DEFAULT NULL,
  `label` varchar(40) DEFAULT NULL,
  `prompt` text,
  `enabled` tinyint DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` varchar(255) DEFAULT NULL,
  `user_text` varchar(500) DEFAULT NULL,
  `boat_text` varchar(500) NOT NULL,
  `motion` varchar(255) DEFAULT NULL,
  `choice_id` int DEFAULT NULL,
  `user_operation` varchar(100) DEFAULT NULL,
  `role` varchar(20) DEFAULT 'basic',
  `sound_effect` varchar(255) DEFAULT NULL,
  `special_effect` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=44 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_choice` (
  `id` int NOT NULL AUTO_INCREMENT,
  `choice_id` int DEFAULT NULL,
  `user_operation` varchar(100) DEFAULT 'chat',
  `choice_text` varchar(500) DEFAULT NULL,
  `choice_action` varchar(100) DEFAULT NULL,
  `boat_text` varchar(500) DEFAULT NULL,
  `motion` varchar(255) DEFAULT NULL,
  `next_choice_id` int DEFAULT NULL,
  `visible` tinyint(1) DEFAULT '1',
  `sound_effect` varchar(255) DEFAULT NULL,
  `special_effect` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_next_choice` (`next_choice_id`),
  KEY `fk_choice_conv` (`choice_id`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `effect` (
  `id` int NOT NULL AUTO_INCREMENT,
  `soundeffect` varchar(255) DEFAULT NULL,
  `location` varchar(255) DEFAULT NULL,
  `live2d_location` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `event_book` (
  `id` int NOT NULL AUTO_INCREMENT,
  `event_type` varchar(40) NOT NULL,
  `content` varchar(500) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=644 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `idle_activity` (
  `id` int NOT NULL AUTO_INCREMENT,
  `theme` varchar(500) NOT NULL,
  `category` varchar(20) NOT NULL DEFAULT 'simple',
  `enabled` tinyint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=91 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `live2d_expression_map` (
  `id` int NOT NULL AUTO_INCREMENT,
  `image_name` varchar(150) NOT NULL COMMENT '图片表情名(如 aluona_kaixin.png)',
  `expression_files` varchar(300) NOT NULL COMMENT 'Live2D表情文件(多个用,分隔)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_image` (`image_name`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `live2d_part` (
  `id` int NOT NULL AUTO_INCREMENT,
  `part_group` varchar(50) NOT NULL,
  `part_name` varchar(100) NOT NULL,
  `params` text NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `live2d_rule` (
  `id` int NOT NULL AUTO_INCREMENT,
  `boat_text` varchar(500) DEFAULT NULL,
  `situation` varchar(500) NOT NULL,
  `expression` text,
  `action` varchar(100) DEFAULT NULL,
  `sound_effect` varchar(200) DEFAULT NULL,
  `special_effect` varchar(200) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=45 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation` (
  `id` int NOT NULL AUTO_INCREMENT,
  `operation` varchar(100) NOT NULL,
  `功能` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pet_memory` (
  `id` int NOT NULL AUTO_INCREMENT,
  `character_name` varchar(50) DEFAULT '阿罗娜',
  `content` text,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=391 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pictionary_game` (
  `id` int NOT NULL AUTO_INCREMENT,
  `word` varchar(40) NOT NULL,
  `aliases` varchar(200) DEFAULT '',
  `category` varchar(20) DEFAULT '',
  `difficulty` tinyint DEFAULT '1',
  `started_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `ended_at` datetime DEFAULT NULL,
  `result` varchar(16) DEFAULT NULL,
  `guess_count` int DEFAULT '0',
  `duration_sec` int DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=51 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pictionary_guess` (
  `id` int NOT NULL AUTO_INCREMENT,
  `game_id` int NOT NULL,
  `round_no` int NOT NULL,
  `guess` varchar(60) DEFAULT '',
  `say` varchar(200) DEFAULT '',
  `changes_desc` varchar(200) DEFAULT '',
  `correct` tinyint DEFAULT '0',
  `shot` varchar(120) DEFAULT '',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pg_game` (`game_id`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pictionary_word` (
  `id` int NOT NULL AUTO_INCREMENT,
  `word` varchar(40) NOT NULL,
  `aliases` varchar(200) DEFAULT '',
  `category` varchar(20) DEFAULT '',
  `difficulty` tinyint DEFAULT '1',
  `enabled` tinyint DEFAULT '1',
  `used_count` int DEFAULT '0',
  `last_used` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pw_word` (`word`)
) ENGINE=InnoDB AUTO_INCREMENT=201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `response_rule` (
  `id` int NOT NULL AUTO_INCREMENT,
  `keyword` varchar(200) DEFAULT NULL,
  `motion` varchar(100) DEFAULT NULL,
  `sound_effect` varchar(100) DEFAULT NULL,
  `special_effect` varchar(200) DEFAULT NULL,
  `action` varchar(100) DEFAULT NULL,
  `weight` int DEFAULT '1',
  `sample_text` text,
  `grow_count` int NOT NULL DEFAULT '0',
  `cooldown` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=260 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `style_exemplars` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tags` varchar(255) NOT NULL DEFAULT '',
  `content` text NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `test_history` (
  `id` int NOT NULL AUTO_INCREMENT,
  `response` text NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1689 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tts_reference` (
  `id` int NOT NULL AUTO_INCREMENT,
  `wav_path` varchar(500) NOT NULL,
  `prompt_text` text NOT NULL,
  `enabled` tinyint DEFAULT '1',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


