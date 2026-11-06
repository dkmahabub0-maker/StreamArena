-- Allow trusted admins (app_metadata.role = 'admin') to manage tournaments.
-- Regular users can only read tournaments; free/open registration remains separately enforced.
CREATE POLICY "Admins manage tournaments"
ON public.tournaments
FOR ALL
TO authenticated
USING ((select auth.jwt() -> 'app_metadata' ->> 'role') = 'admin')
WITH CHECK ((select auth.jwt() -> 'app_metadata' ->> 'role') = 'admin');

-- Admins may review all registrations; users retain access only to their own rows.
CREATE POLICY "Admins view all tournament registrations"
ON public.tournament_registrations
FOR SELECT
TO authenticated
USING ((select auth.jwt() -> 'app_metadata' ->> 'role') = 'admin');
