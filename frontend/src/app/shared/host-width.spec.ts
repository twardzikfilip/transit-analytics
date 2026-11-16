import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { hostWidth } from './host-width';

@Component({ template: '' })
class Sized {
  readonly width = hostWidth(600, 280);
}

describe('hostWidth', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('starts at the initial width and follows the element, never below the minimum', () => {
    let callback: ResizeObserverCallback = () => undefined;
    const disconnect = vi.fn();
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: ResizeObserverCallback) {
          callback = cb;
        }
        observe() {}
        disconnect = disconnect;
      },
    );
    const fixture = TestBed.createComponent(Sized);
    const width = fixture.componentInstance.width;
    expect(width()).toBe(600);

    const resize = (w: number) =>
      callback([{ contentRect: { width: w } } as ResizeObserverEntry], {} as ResizeObserver);
    resize(912.7);
    expect(width()).toBe(912);
    resize(100);
    expect(width()).toBe(280);

    fixture.destroy();
    expect(disconnect).toHaveBeenCalled();
  });
});
