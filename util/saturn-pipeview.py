#!/usr/bin/env python3
#
# Generates a trace compatible with the gem5 o3-pipeview.py visualizer
# script from a Saturn simulation log file.
#
# Each element group of a multi-cycle vector instruction is depicted as
# a separate uop.  In this context, "retirement" is commonly defined as
# the cycle when the first result that a given uop produces is written
# back to the register file.  This rendering is useful since it marks
# the earliest point at which vector chaining is theoretically possible.
#
# Vector loads:
# - One uop represents an individual memory request
# - "Issue": cycle in which the load request leaves the VMU interface
# - "Retirement": earliest cycle in which any part of the load data is
#   written back to the register file (i.e., the first segment)
#

import argparse
import collections
import dataclasses
import typing
import enum
import sys

class InsnType(enum.Flag):
    COMPUTE = enum.auto()
    LOAD = enum.auto()
    STORE = enum.auto()

parser = argparse.ArgumentParser(description='Generate o3-pipeview trace for Saturn from simulation *.out file')
parser.add_argument('-c', '--cycle-time', metavar='N', type=int,
    default=1000, help='cycle time in ticks')
parser.add_argument('-X', '--compute', dest='select', action='append_const',
    const=InsnType.COMPUTE, help='show only compute (non-memory) instructions')
parser.add_argument('-L', '--load', dest='select', action='append_const',
    const=InsnType.LOAD, help='show only load instructions')
parser.add_argument('-S', '--store', dest='select', action='append_const',
    const=InsnType.STORE, help='show only store instructions')
parser.add_argument('-u', '--unretired', action='store_true', help='show unretired instructions')
parser.add_argument('input', type=argparse.FileType('r'), help='simulation log file')
args = parser.parse_args()

if args.select:
    select = args.select[0]
    for flag in args.select[1:]:
        select |= flag
else:
    select = list(InsnType)


@dataclasses.dataclass
class Event:
    cycle: int
    eg: int

@dataclasses.dataclass
class Insn:
    dasm: str
    pc: int
    dispatch: int
    issue: typing.List[Event] = dataclasses.field(default_factory=list)
    retire: typing.List[Event] = dataclasses.field(default_factory=list)
    kind: InsnType = InsnType.COMPUTE

insns = collections.OrderedDict()

with args.input as log:
    for n, line in enumerate(log):
        if not line.startswith('PipeView:'):
            continue
        fields = line.rstrip().split(':')

        seqno = int(fields[1])
        label = fields[2]
        cycle = int(fields[3]) * args.cycle_time

        if label == 'dispatch':
            pc = int(fields[4], base=16)
            dasm = fields[7]
            insns[seqno] = Insn(dasm, pc, cycle)
            continue

        insn = insns.get(seqno)
        if insn is None:
           print(f'warning: unknown sequence number {seqno} (line {n})', file=sys.stderr)
           continue

        if label == 'vxsop':
            eg = int(fields[4])
            insn.issue.append(Event(cycle, eg))
        elif label == 'vssop':
            eg = int(fields[4])
            insn.issue.append(Event(cycle, eg))
            insn.kind = InsnType.STORE
        elif label == 'lasop':
            eg = int(fields[5])
            insn.issue.append(Event(cycle, eg))
            insn.kind = InsnType.LOAD
        elif label == 'sasop':
            eg = int(fields[5])
            insn.retire.append(Event(cycle, eg))
            insn.kind = InsnType.STORE
        elif label.startswith('write.'):
            eg = int(fields[4])
            insn.retire.append(Event(cycle, eg))

for seqno, insn in insns.items():
    if insn.kind not in select:
        continue

    if args.unretired and not insn.issue:
        fetch_cycle = insn.dispatch - 1 # TODO
        print(f'O3PipeView:fetch:{fetch_cycle}:0x{insn.pc:x}:{n}:{seqno}:{insn.dasm}')
        print('O3PipeView:decode:0')
        print('O3PipeView:rename:0')
        print(f'O3PipeView:dispatch:{insn.dispatch}')
        print('O3PipeView:issue:0')
        print('O3PipeView:complete:0')
        print('O3PipeView:retire:0')
        continue

    for n, issue in enumerate(insn.issue):
        fetch_cycle = insn.dispatch - 1 # TODO
        retire_cycle = 0
        for retire in insn.retire:
            if retire.eg == issue.eg:
                retire_cycle = retire.cycle
                break

        if n == 0:
            prefix = '┏ '
        elif n < len(insn.issue) - 1:
            prefix = '┣ '
        else:
            prefix = '┗ '

        print(f'O3PipeView:fetch:{fetch_cycle}:0x{insn.pc:x}:{n}:{seqno}:{prefix}{insn.dasm}')
        print('O3PipeView:decode:0')
        print('O3PipeView:rename:0')
        print(f'O3PipeView:dispatch:{insn.dispatch}')
        print(f'O3PipeView:issue:{issue.cycle}')
        print('O3PipeView:complete:0')
        print(f'O3PipeView:retire:{retire_cycle}')
