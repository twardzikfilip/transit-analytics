import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** What a new user's name and password must be; the backend checks the same (UserRules). */
export const NAME_MIN = 3;
export const NAME_MAX = 20;
export const PASSWORD_MIN = 10;
/** Of lowercase, uppercase, digits and other characters, a password has at least this many kinds. */
export const PASSWORD_KINDS = 2;

/** A name with no spaces, not starting with a digit. */
export function nameShape(control: AbstractControl<string>): ValidationErrors | null {
  const v = control.value ?? '';
  if (/\s/.test(v)) return { spaces: true };
  if (/^\d/.test(v)) return { startsWithDigit: true };
  return null;
}

/** A name nobody has yet, in any case ("Ann" and "ann" are one name). */
export function nameFree(taken: () => readonly string[]): ValidatorFn {
  return (control) => {
    const v = (control.value ?? '').trim().toLowerCase();
    return v && taken().some((n) => n.toLowerCase() === v) ? { taken: true } : null;
  };
}

/** How many kinds of character a password has: lowercase, uppercase, digits, others. */
export function kinds(password: string): number {
  return [/\p{Ll}/u, /\p{Lu}/u, /\d/, /[^\p{L}\d]/u].filter((r) => r.test(password)).length;
}

export function passwordKinds(control: AbstractControl<string>): ValidationErrors | null {
  const v = control.value ?? '';
  return v && kinds(v) < PASSWORD_KINDS ? { kinds: true } : null;
}

/** On the form: the password typed twice the same. */
export function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const password = group.get('password')?.value;
  const confirm = group.get('confirm')?.value;
  return confirm && password !== confirm ? { mismatch: true } : null;
}
