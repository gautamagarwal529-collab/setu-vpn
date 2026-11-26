use std::cmp::Reverse;
use std::collections::{HashMap, HashSet, VecDeque};

use crate::directory::HOUR;

// One input row corresponds to one element of the Java relay list.
pub(crate) const FIELDS: usize = 7;
pub(crate) const INDIA: u16 = u16::from_be_bytes(*b"IN");

/// Current relays with a recent TCP response come first, followed by other current relays.
pub(crate) fn availability(reachable: bool, current: bool) -> u8 {
    if reachable {
        0
    } else if current {
        1
    } else {
        2
    }
}

pub(crate) fn order(
    rows: &[[i64; FIELDS]],
    directory: bool,
    previous: u16,
    seed: Option<u64>,
) -> Vec<usize> {
    let mut indices: Vec<_> = (0..rows.len()).collect();
    if directory {
        indices.sort_by_key(|&i| {
            let [_, _, state, _, _, seen, score] = rows[i];
            (state, Reverse(seen / (3 * HOUR)), Reverse(score))
        });
    } else {
        indices.sort_by_key(|&i| {
            let [_, _, state, ping, speed, seen, _] = rows[i];
            (
                state,
                if ping > 0 { ping } else { i32::MAX.into() },
                Reverse(speed),
                Reverse(seen),
            )
        });
    }

    let mut seen = HashSet::with_capacity(indices.len());
    indices.retain(|&i| {
        let [ip, country, ..] = rows[i];
        seen.insert((ip, if directory { country } else { 0 }))
    });
    if let Some(seed) = seed {
        indices = rotate(indices, rows, previous, seed);
    }
    indices
}

/// SplitMix64 produces a different rotation for each seed supplied by Android.
struct Rotation(u64);

impl Rotation {
    fn index(&mut self, limit: usize) -> usize {
        self.0 = self.0.wrapping_add(0x9e3779b97f4a7c15);
        let mut n = self.0;
        n = (n ^ (n >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        n = (n ^ (n >> 27)).wrapping_mul(0x94d049bb133111eb);
        ((n ^ (n >> 31)) % limit as u64) as usize
    }
}

fn rotate(ranked: Vec<usize>, rows: &[[i64; FIELDS]], previous: u16, seed: u64) -> Vec<usize> {
    let country = |i: usize| rows[i][1] as u16;
    let mut offsets = HashMap::new();
    let mut countries: Vec<VecDeque<usize>> = Vec::new();
    let mut india = VecDeque::new();
    for &i in &ranked {
        let code = country(i);
        if code == INDIA {
            india.push_back(i);
        } else {
            let index = *offsets.entry(code).or_insert_with(|| {
                countries.push(VecDeque::new());
                countries.len() - 1
            });
            countries[index].push_back(i);
        }
    }
    if countries.is_empty() {
        return ranked;
    }

    let mut random = Rotation(seed);
    for upper in (1..countries.len()).rev() {
        countries.swap(upper, random.index(upper + 1));
    }
    if countries.len() > 1 && country(countries[0][0]) == previous {
        let other = 1 + random.index(countries.len() - 1);
        countries.swap(0, other);
    }

    let mut ordered = Vec::with_capacity(ranked.len());
    if !india.is_empty() && previous != INDIA && random.index(25) == 0 {
        ordered.extend(india.pop_front());
    }
    let mut remaining: VecDeque<_> = countries.into();
    while let Some(mut relays) = remaining.pop_front() {
        ordered.extend(relays.pop_front());
        if !relays.is_empty() {
            remaining.push_back(relays);
        }
    }
    ordered.extend(india);
    ordered
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranking_and_deduplication_match_the_two_callers() {
        let rows = [
            [1, i64::from(INDIA), 1, 20, 500, 50, 600],
            [1, 2, 0, 10, 100, 60, 600],
            [2, 2, 0, 10, 300, 70, 500],
            [3, 3, 2, 1, 900, 80, 400],
        ];
        assert_eq!(order(&rows, false, 0, None), [2, 1, 3]);
        assert_eq!(order(&rows, true, 0, None), [1, 2, 0, 3]);
    }

    #[test]
    fn global_rotation_avoids_repeats_and_prefers_international_relays() {
        let rows = [
            [1, i64::from(INDIA), 0, 1, 100, 0, 0],
            [2, 2, 0, 2, 100, 0, 0],
            [3, 2, 0, 3, 100, 0, 0],
            [4, 3, 0, 4, 100, 0, 0],
        ];
        let mut india_first = 0;
        for seed in 0..2048 {
            let order = order(&rows, false, 2, Some(seed));
            assert_ne!(rows[order[0]][1], 2);
            if order[0] == 0 {
                india_first += 1;
            } else {
                assert_eq!(order[order.len() - 1], 0);
                assert_ne!(rows[order[0]][1], rows[order[1]][1]);
            }
        }
        assert!((30..140).contains(&india_first));
    }
}
