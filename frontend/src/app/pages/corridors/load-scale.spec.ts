import { MAX_WIDTH, colorClasses, colorFor, widthFor } from './load-scale';

describe('segment load scales', () => {
  it('scales width linearly from 1 px to the maximum', () => {
    expect(widthFor(0, 200)).toBe(1);
    expect(widthFor(200, 200)).toBe(MAX_WIDTH);
    expect(widthFor(100, 200)).toBe(7.5);
    expect(widthFor(5, 0)).toBe(1);
  });

  it('splits 0 to the maximum into round equal classes, light to dark', () => {
    const classes = colorClasses(230);
    expect(classes.map((c) => [c.from, c.to])).toEqual([
      [0, 50],
      [50, 100],
      [100, 150],
      [150, 200],
      [200, 250],
    ]);
    expect(classes[0].color).toBe('#86b6ef');
    expect(classes[classes.length - 1].color).toBe('#0d366b');
  });

  it('puts a number of buses in its class', () => {
    const classes = colorClasses(230);
    expect(colorFor(0, classes)).toBe(classes[0].color);
    expect(colorFor(50, classes)).toBe(classes[1].color);
    expect(colorFor(230, classes)).toBe(classes[4].color);
  });
});
