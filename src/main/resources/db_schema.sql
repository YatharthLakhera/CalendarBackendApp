create database calendar_db;
use calendar_db;

create table user_details (
user_id binary(16) not null primary key,
name varchar(128) not null,
email varchar(128) not null,
default_availability json default null
);

create table user_custom_availability (
id int(16) not null AUTO_INCREMENT primary key,
user_id binary(16) not null,
availability_date date not null,
time_zone enum('IST') not null,
is_available tinyint(1) not null default 1,
availability varchar(512) default null,
key `UCA_user_id_date_key` (user_id, availability_date),
constraint `UCA_user_id_fk` foreign key (user_id) references user_details(user_id)
);

create table event_scheduler (
event_id binary(16) not null primary key,
title varchar(128) not null,
description text default null,
event_date date not null,
start_time varchar(4) not null,
end_time varchar(4) not null,
time_zone enum('IST') not null,
key `ES_event_date_key` (event_date)
);

create table event_audience (
id int(16) not null AUTO_INCREMENT primary key,
event_id binary(16) not null,
user_id binary(16) not null,
role enum('ORGANISER', 'PARTICIPANT') not null default 'PARTICIPANT',
status enum('PENDING', 'ACCEPTED', 'MAYBE', 'DECLINED') not null default 'PENDING',
key `EA_event_id_user_id_key` (event_id, user_id),
constraint `EA_event_id_fk` foreign key (event_id) references event_scheduler(event_id),
constraint `EA_user_id_fk` foreign key (user_id) references user_details(user_id)
);

-- SELECT * FROM user_details WHERE id = UNHEX(REPLACE('1728dfe4-a197-47bf-9c4c-c80011c58f9e', '-', ''));
