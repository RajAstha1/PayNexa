-- Database bootstrap: one schema per service, one migrator (owns DDL) and one runtime role (DML only) per schema.
-- Run once by a superuser/admin. Passwords come from psql variables, never from this file:
--   psql -v identity_migrator_pw=... -v identity_app_pw=... -v payments_migrator_pw=... -v payments_app_pw=... \
--        -v notification_migrator_pw=... -v notification_app_pw=... -f 01-roles-and-schemas.sql
-- Least privilege: the runtime roles can neither change the schema nor, for the ledger, rewrite history.

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------------------------------- roles
CREATE ROLE identity_migrator     LOGIN PASSWORD :'identity_migrator_pw'     NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE identity_app          LOGIN PASSWORD :'identity_app_pw'          NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE payments_migrator     LOGIN PASSWORD :'payments_migrator_pw'     NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE payments_app          LOGIN PASSWORD :'payments_app_pw'          NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE notification_migrator LOGIN PASSWORD :'notification_migrator_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE notification_app      LOGIN PASSWORD :'notification_app_pw'      NOSUPERUSER NOCREATEDB NOCREATEROLE;

-- Nobody gets implicit rights through PUBLIC.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
DO $$ BEGIN EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC', current_database()); END $$;

-- ---------------------------------------------------------------------------------------------------- schemas
CREATE SCHEMA identity     AUTHORIZATION identity_migrator;
CREATE SCHEMA payments     AUTHORIZATION payments_migrator;
CREATE SCHEMA notifications AUTHORIZATION notification_migrator;

GRANT USAGE ON SCHEMA identity      TO identity_app;
GRANT USAGE ON SCHEMA payments      TO payments_app;
GRANT USAGE ON SCHEMA notifications TO notification_app;

-- Each service can see only its own schema (no cross-service table access).
ALTER ROLE identity_app          SET search_path = identity;
ALTER ROLE payments_app          SET search_path = payments;
ALTER ROLE notification_app      SET search_path = notifications;
ALTER ROLE identity_migrator     SET search_path = identity;
ALTER ROLE payments_migrator     SET search_path = payments;
ALTER ROLE notification_migrator SET search_path = notifications;

-- Bounded damage from runaway queries.
ALTER ROLE identity_app     SET statement_timeout = '10s';
ALTER ROLE payments_app     SET statement_timeout = '15s';
ALTER ROLE notification_app SET statement_timeout = '10s';
ALTER ROLE payments_app     SET idle_in_transaction_session_timeout = '30s';

-- ---------------------------------------------------------------------------------------------------- default grants
-- Applied to every table/sequence the migrator creates in the future, so new migrations need no manual GRANTs.
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO identity_app;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
    GRANT USAGE, SELECT ON SEQUENCES TO identity_app;

ALTER DEFAULT PRIVILEGES FOR ROLE payments_migrator IN SCHEMA payments
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payments_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payments_migrator IN SCHEMA payments
    GRANT USAGE, SELECT ON SEQUENCES TO payments_app;

ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA notifications
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_app;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA notifications
    GRANT USAGE, SELECT ON SEQUENCES TO notification_app;
