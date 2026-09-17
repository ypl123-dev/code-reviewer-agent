-- V1__init.sql 初始化数据库结构

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 审查记录表
CREATE TABLE IF NOT EXISTS code_review_record (
    id BIGSERIAL PRIMARY KEY,
    project_key VARCHAR(255) NOT NULL,
    mr_iid BIGINT NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    total_tokens INT DEFAULT 0,
    llm_calls INT DEFAULT 0,
    tool_calls INT DEFAULT 0,
    duration_ms BIGINT DEFAULT 0,
    review_result TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_project_mr ON code_review_record(project_key, mr_iid);
CREATE INDEX IF NOT EXISTS idx_review_commit ON code_review_record(commit_sha);

-- 团队代码规范表（向量化存储在 code_knowledge 表中，这里存元数据）
CREATE TABLE IF NOT EXISTS code_standard_doc (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    doc_source VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_standard_category ON code_standard_doc(category);

-- 历史 Issue 记录（向量化用于检索）
CREATE TABLE IF NOT EXISTS review_history_issue (
    id BIGSERIAL PRIMARY KEY,
    project_key VARCHAR(255) NOT NULL,
    title VARCHAR(512) NOT NULL,
    code_snippet TEXT,
    issue_type VARCHAR(64),
    fix_solution TEXT,
    severity VARCHAR(32),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_history_project ON review_history_issue(project_key);

-- 项目配置表
CREATE TABLE IF NOT EXISTS project_config (
    id BIGSERIAL PRIMARY KEY,
    project_key VARCHAR(255) NOT NULL UNIQUE,
    config_json JSONB,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 工具调用记录表
-- 记录 LLM 每次 Function Calling 的工具名、入参、出参与耗时，用于回溯审查决策过程
CREATE TABLE IF NOT EXISTS tool_call_log (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT REFERENCES code_review_record(id),
    tool_name VARCHAR(128) NOT NULL,
    tool_input TEXT,
    tool_output TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tool_review ON tool_call_log(review_id);

-- 对话会话表（业务侧记录，记忆本身存 Redis）
CREATE TABLE IF NOT EXISTS chat_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    review_id BIGINT REFERENCES code_review_record(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_session ON chat_session(session_id);
