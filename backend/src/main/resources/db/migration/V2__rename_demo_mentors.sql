-- Rename the demo mentors on databases created before the names were changed (no-op elsewhere).
update users set full_name = 'Dr. Sunitha K', updated_at = now() where email = 'mentor1@mentora.edu' and full_name = 'Dr. Meera Krishnan';
update users set full_name = 'Ms. Nivedita G. Y.', updated_at = now() where email = 'mentor2@mentora.edu' and full_name = 'Prof. Rajesh Kulkarni';
