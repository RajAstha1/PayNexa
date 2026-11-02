"use client";
import { useId, useState, type Ref } from "react";
import { Icon } from "../icons";

/**
 * A password input with a text "Show / Hide" toggle. The toggle is a real button that reports its state, the value
 * is never copied anywhere, and the field goes back to hidden whenever the form is submitted.
 */
export function PasswordField({ id, label, value, onChange, onBlur, error, hint, autoComplete, minLength, inputRef, hidden }: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  error?: string | null;
  hint?: string;
  autoComplete: "current-password" | "new-password";
  /** When set, shows a small "n / minLength" counter that turns green once met. */
  minLength?: number;
  inputRef?: Ref<HTMLInputElement>;
  /** Parent forces the field back to hidden (e.g. after submit). Changing this value re-hides it. */
  hidden?: number;
}) {
  const [shown, setShown] = useState(false);
  const [seenHidden, setSeenHidden] = useState(hidden);
  if (hidden !== seenHidden) {
    // Adjust state during render when the parent asks for a re-hide (avoids an effect that sets state).
    setSeenHidden(hidden);
    setShown(false);
  }
  const noteId = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  const met = minLength !== undefined && value.length >= minLength;
  return (
    <div className={`field${error ? " field-error" : ""}`}>
      <label htmlFor={id}>{label}</label>
      <div className="input-wrap">
        <input
          id={id}
          ref={inputRef}
          type={shown ? "text" : "password"}
          value={value}
          autoComplete={autoComplete}
          autoCapitalize="none"
          spellCheck={false}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          aria-invalid={!!error}
          aria-describedby={describedBy}
          required
        />
        <button
          type="button"
          className="reveal"
          aria-pressed={shown}
          aria-controls={id}
          aria-label={shown ? "Hide password" : "Show password"}
          onClick={() => setShown((s) => !s)}
        >
          <Icon name={shown ? "eye-off" : "eye"} size={16} />
          <span aria-hidden="true">{shown ? "Hide" : "Show"}</span>
        </button>
      </div>
      <div className="field-foot" id={noteId}>
        {error ? (
          <p className="field-msg" id={`${id}-error`}><Icon name="alert" size={14} /> {error}</p>
        ) : hint ? (
          <p className="field-hint" id={`${id}-hint`}>{hint}</p>
        ) : null}
        {minLength !== undefined && value.length > 0 && (
          <span className={`field-count num${met ? " met" : ""}`} aria-hidden="true">{Math.min(value.length, 999)} / {minLength}</span>
        )}
      </div>
    </div>
  );
}
