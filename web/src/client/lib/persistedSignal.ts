import { createSignal, createEffect, type Accessor, type Setter } from 'solid-js';

/**
 * Create a signal that persists its value to localStorage.
 * 
 * @param key - localStorage key
 * @param defaultValue - default value if nothing in storage
 * @returns [getter, setter] tuple like createSignal
 */
export function createPersistedSignal<T>(
  key: string,
  defaultValue: T
): [Accessor<T>, Setter<T>] {
  // Read initial value from localStorage
  const stored = localStorage.getItem(key);
  const initial = stored !== null ? JSON.parse(stored) as T : defaultValue;
  
  const [value, setValue] = createSignal<T>(initial);
  
  // Persist to localStorage on change
  createEffect(() => {
    localStorage.setItem(key, JSON.stringify(value()));
  });
  
  return [value, setValue];
}

