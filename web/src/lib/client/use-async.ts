"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "./api";

export interface AsyncState<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => void;
}

interface Options<T> {
  /** Re-fetch on this interval while `pollWhile(data)` is true (e.g. a payment that has not reached a final status). */
  pollMs?: number;
  pollWhile?: (data: T) => boolean;
  /** When false, nothing is fetched (e.g. admin-only data for a regular user). */
  enabled?: boolean;
}

/**
 * Loads data with an AbortSignal so a slow response can never overwrite a newer one or update an unmounted page.
 * `loader` is read through a ref, so callers can pass an inline function without causing refetch loops.
 */
export function useAsync<T>(loader: (signal: AbortSignal) => Promise<T>, deps: unknown[], options: Options<T> = {}): AsyncState<T> {
  const { pollMs, pollWhile, enabled = true } = options;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [tick, setTick] = useState(0);
  const loaderRef = useRef(loader);
  const pollWhileRef = useRef(pollWhile);
  useEffect(() => {
    loaderRef.current = loader;
    pollWhileRef.current = pollWhile;
  });

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let first = true;

    const run = async () => {
      if (first) setLoading(true);
      try {
        const result = await loaderRef.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(result);
        setError(null);
        if (pollMs && pollWhileRef.current?.(result)) timer = setTimeout(run, pollMs);
      } catch (e) {
        if (controller.signal.aborted || (e instanceof DOMException && e.name === "AbortError")) return;
        setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "Something went wrong."));
        if (pollMs && !first) timer = setTimeout(run, pollMs * 2); // back off while failing
      } finally {
        if (!controller.signal.aborted) setLoading(false);
        first = false;
      }
    };
    void run();

    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled, pollMs]);

  return { data, error, loading, reload };
}
