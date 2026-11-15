-- BridgeAccess migration 5: quiz_questions.category
-- Databases created before the column was added to bridgeaccess_schema_full.sql are
-- missing it. The bundle query (CatalogueRepository.findQuestions) and
-- seed_sample_packages.sql both use it, so a missing column makes every package
-- download fail with a 500. Additive and safe to run more than once.

alter table app.quiz_questions
  add column if not exists category text;
