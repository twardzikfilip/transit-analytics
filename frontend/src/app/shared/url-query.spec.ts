import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { openFormerQueryLink } from './url-query';

@Component({ template: '' })
class Blank {}

describe('openFormerQueryLink', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'brigades',
            children: [
              {
                path: '',
                component: Blank,
                canActivate: [openFormerQueryLink('brigade', ['/brigades'])],
              },
              { path: ':brigade', component: Blank },
            ],
          },
        ]),
      ],
    }),
  );

  it("sends a former link to the item's own page, keeping the other parameters", async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/brigades?brigade=103-05&date=2026-11-20');
    expect(TestBed.inject(Router).url).toBe('/brigades/103-05?date=2026-11-20');
  });

  it('leaves the list alone without the parameter', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/brigades?agency=BKM');
    expect(TestBed.inject(Router).url).toBe('/brigades?agency=BKM');
  });
});
