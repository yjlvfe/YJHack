# GlobalActionBudget Implementation Plan

## Overview
A shared ceiling for AutoLeft + AutoRight combined. No cross-module bursts.
This is NOT camouflage — it's engineering discipline to prevent the two
modules from overlapping in the same tick in a way that looks non-human.

## What It Does
- One global budget per tick: max 2 synthetic actions total per tick
- AutoLeft and AutoRight share this budget — first-come, first-served
- If both want to fire in the same tick, only 2 total are allowed
- Exceeding actions are dropped silently (not queued, not replayed)

## Implementation
- New file: `core/ActionBudget.java`
- Tick rate: 20 ticks/sec, budget = 2 per tick
- AutoLeft.frame() → asks budget before doAttack()
- AutoRight.tickRightAutoClick() → asks budget before queuePress()

## Why This Matters
Without it: AutoLeft does 20 cps + AutoRight does 20 cps = 40 combined.
That's 40 actions in one second — no human does that.
With it: max 40 combined per second, fairly distributed between both hands.

## What It Is NOT
- NOT a pattern randomizer
- NOT a human behavior simulator
- Just a hard engineering ceiling that prevents accidental double-bursts
