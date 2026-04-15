

drop table chat_history;
drop table vector_store;
-- auto-generated definition
create table chat_history
(
    id              bigint      not null
        constraint spring_ai_chat_memory_pkey
            primary key,
    conversation_id varchar(36) not null,
    message_type    varchar(10) not null,
    content         text        not null,
    timestamp       timestamp   not null
);

-- auto-generated definition
create table vector_store
(
    id        uuid default uuid_generate_v4() not null
        primary key,
    content   text,
    metadata  json,
    embedding vector(384)
);


create index spring_ai_vector_index
    on vector_store using hnsw (embedding public.vector_cosine_ops);

