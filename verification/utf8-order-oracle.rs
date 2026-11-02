extern crate alloc;

#[path = "../tmp/regex-syntax/src/utf8.rs"]
mod utf8;

#[cfg(test)]
mod parity {
    use super::utf8::{Utf8Range, Utf8Sequence};

    fn range(start: u8, end: u8) -> Utf8Range { Utf8Range { start, end } }

    #[test]
    fn variants_order_before_ranges() {
        let variants = [
            Utf8Sequence::One(range(255, 255)),
            Utf8Sequence::Two([range(128, 128), range(255, 255)]),
            Utf8Sequence::Three([range(1, 1), range(255, 255), range(255, 255)]),
            Utf8Sequence::Four([range(0, 0); 4]),
        ];
        for (i, left) in variants.iter().enumerate() {
            for (j, right) in variants.iter().enumerate() {
                assert_eq!(left.cmp(right), i.cmp(&j));
            }
        }
        let mut sorted = variants;
        sorted.reverse();
        sorted.sort();
        assert_eq!(sorted, variants);
    }

    #[test]
    fn equal_variants_compare_ranges_lexicographically() {
        let low = Utf8Sequence::Two([range(127,127), range(255,255)]);
        let high = Utf8Sequence::Two([range(128,128), range(0,0)]);
        assert!(low < high);
        assert!(Utf8Sequence::One(range(128,129)) < Utf8Sequence::One(range(128,130)));
        assert_eq!(low.cmp(&low.clone()), std::cmp::Ordering::Equal);
    }

    #[test]
    fn real_ranges_order_before_and_after_reversal() {
        let one = Utf8Sequence::One(range(0,127));
        let mut two = Utf8Sequence::Two([range(0xc2,0xdf),range(0x80,0xbf)]);
        let mut three = Utf8Sequence::Three([range(0xe0,0xe0),range(0xa0,0xbf),range(0x80,0xbf)]);
        let four = Utf8Sequence::Four([range(0xf0,0xf0),range(0x90,0xbf),range(0x80,0xbf),range(0x80,0xbf)]);
        let mut sorted = [four,two,three,one];
        sorted.sort();
        assert_eq!(sorted, [one,two,three,four]);
        two.reverse();
        three.reverse();
        assert!(two < three);
    }
}
