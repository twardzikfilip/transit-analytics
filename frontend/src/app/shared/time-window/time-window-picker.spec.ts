import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { renderPage, text } from '../../testing/page-testing';
import { AgencyOption, pickerLabel, toggleAgency } from './agency-picker';
import { TimeWindowPicker } from './time-window-picker';

const OPTIONS: AgencyOption[] = [
  { name: 'BKM', vehicles: 262 },
  { name: 'TUROSN', vehicles: 3 },
  { name: 'WSCHODEXPRESS', vehicles: 31 },
];

@Component({
  imports: [TimeWindowPicker],
  template: `<app-time-window defaultPreset="1w" [agencies]="options" [reset]="['page']">
    <span class="extra">Line</span>
  </app-time-window>`,
})
class Host {
  options = OPTIONS;
}

@Component({
  imports: [TimeWindowPicker],
  template: `<app-time-window />`,
})
class Plain {}

describe('time window picker', () => {
  it('shows the window in the URL, presets, operators and what the page adds', () => {
    const { el } = renderPage(Host, {}, { from: '2026-11-16T05:00', to: '2026-11-16T17:00' });
    const inputs = [...el.querySelectorAll('input[type=datetime-local]')] as HTMLInputElement[];
    expect(inputs.map((i) => i.value)).toEqual(['2026-11-16T05:00', '2026-11-16T17:00']);
    expect(el.querySelectorAll('.presets button')).toHaveLength(11);
    expect(el.querySelector('.presets button.active')).toBeNull();
    expect(text(el.querySelector('app-agency-picker'))).toContain('All operators');
    expect(text(el.querySelector('.extra'))).toBe('Line');
  });

  it("marks the page's default preset, and all time has no start", () => {
    const { el, setQuery } = renderPage(Host, {});
    expect(text(el.querySelector('.presets button.active'))).toBe('1 week');
    setQuery({ range: 'all' });
    expect(text(el.querySelector('.presets button.active'))).toBe('All time');
    expect((el.querySelector('input[type=datetime-local]') as HTMLInputElement).value).toBe('');
    expect(text(el)).toContain('From the first recording');
  });

  it('puts presets, a custom window and operators in the URL, clearing the reset parameters', () => {
    const { el, fixture, render } = renderPage(Host, {}, { range: '1h' });
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const merge = (queryParams: Record<string, unknown>) => [
      [],
      { queryParams, queryParamsHandling: 'merge' },
    ];

    (el.querySelectorAll('.presets button')[10] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ page: null, range: 'all', from: null, to: null }),
    );

    const to = el.querySelectorAll('input[type=datetime-local]')[1] as HTMLInputElement;
    to.value = '2026-11-16T12:00';
    to.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ page: null, range: null, from: expect.any(String), to: '2026-11-16T12:00' }),
    );
    const calls = navigate.mock.calls.length;
    to.value = '';
    to.dispatchEvent(new Event('change'));
    expect(navigate.mock.calls).toHaveLength(calls);

    (el.querySelector('app-agency-picker button') as HTMLButtonElement).click();
    render();
    const boxes = [...el.querySelectorAll('app-agency-picker input')] as HTMLInputElement[];
    expect(boxes.map((b) => b.checked)).toEqual([true, true, true]);
    boxes[1].click();
    expect(navigate).toHaveBeenLastCalledWith(
      ...merge({ page: null, agency: ['BKM', 'WSCHODEXPRESS'] }),
    );
  });

  it('has no operator dropdown unless the page gives operators', () => {
    const { el } = renderPage(Plain, {});
    expect(el.querySelector('app-agency-picker')).toBeNull();
    expect(text(el.querySelector('.presets button.active'))).toBe('24 h');
  });
});

describe('operator dropdown', () => {
  it('labels the choice and toggles operators, all being none chosen', () => {
    expect(pickerLabel(OPTIONS, [])).toBe('All operators');
    expect(pickerLabel(OPTIONS, ['BKM'])).toBe('BKM');
    expect(pickerLabel(OPTIONS, ['BKM', 'TUROSN'])).toBe('2 of 3 operators');
    expect(pickerLabel(OPTIONS, ['BKM', 'TUROSN', 'WSCHODEXPRESS'])).toBe('All operators');
    expect(toggleAgency(OPTIONS, [], 'TUROSN')).toEqual(['BKM', 'WSCHODEXPRESS']);
    expect(toggleAgency(OPTIONS, ['BKM', 'WSCHODEXPRESS'], 'TUROSN')).toEqual([]);
    expect(toggleAgency(OPTIONS, ['BKM'], 'TUROSN')).toEqual(['BKM', 'TUROSN']);
  });

  it('keeps one operator chosen, selects all again, and closes on a click outside', () => {
    const { el, fixture, render } = renderPage(Host, {}, { agency: 'BKM' });
    const navigate = vi
      .spyOn(fixture.debugElement.injector.get(Router), 'navigate')
      .mockResolvedValue(true);
    const toggle = el.querySelector('app-agency-picker button') as HTMLButtonElement;
    expect(text(toggle)).toBe('BKM ▾');
    toggle.click();
    render();
    const boxes = [...el.querySelectorAll('app-agency-picker input')] as HTMLInputElement[];
    expect(boxes[0].disabled).toBe(true);
    (el.querySelector('app-agency-picker .all') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { page: null, agency: null },
      queryParamsHandling: 'merge',
    });
    document.body.click();
    render();
    expect(el.querySelector('app-agency-picker .panel')).toBeNull();
  });
});
