-- Prevent concurrent requests from creating multiple active streams for one user.
-- Stale stream rows are marked inactive by the application before a new stream starts.
CREATE UNIQUE INDEX IF NOT EXISTS streams_one_live_per_user_idx
  ON public.streams (user_id)
  WHERE is_live = true;
