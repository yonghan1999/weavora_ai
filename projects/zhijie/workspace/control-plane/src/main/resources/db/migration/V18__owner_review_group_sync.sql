CREATE FUNCTION public.notify_owner_review_group_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO public.outbox_events(event_id, group_id, aggregate_id, type, payload, occurred_at, attempts)
    VALUES (gen_random_uuid()::text, NEW.group_id, NEW.group_id, 'group.changed', '{}', CURRENT_TIMESTAMP, 0);
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.notify_owner_review_group_change() FROM PUBLIC;

CREATE TRIGGER owner_review_group_change AFTER UPDATE OF status ON public.owner_recovery_requests
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status IN ('RESOLVED', 'REJECTED'))
    EXECUTE FUNCTION public.notify_owner_review_group_change();
