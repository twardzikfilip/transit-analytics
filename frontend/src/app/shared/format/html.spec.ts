import { escapeHtml } from './html';

describe('escapeHtml', () => {
  it('escapes the characters that would start markup or end an attribute', () => {
    expect(escapeHtml(`<b onclick="x">Tom & Jerry's</b>`)).toBe(
      '&#60;b onclick=&#34;x&#34;&#62;Tom &#38; Jerry&#39;s&#60;/b&#62;',
    );
  });
});
