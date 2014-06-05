/******************************************************************************
 ** This data and information is proprietary to, and a valuable trade secret
 ** of, Basis Technology Corp.  It is given in confidence by Basis Technology
 ** and may only be used as permitted under the license agreement under which
 ** it has been distributed, and in no other way.
 **
 ** Copyright (c) 2014 Basis Technology Corporation All rights reserved.
 **
 ** The technical data and information provided herein are provided with
 ** `limited rights', and the computer software provided herein is provided
 ** with `restricted rights' as those terms are defined in DAR and ASPR
 ** 7-104.9(a).
 ******************************************************************************/

package com.basistech.tclre;

import java.util.BitSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Runtime DFA.
 * Since we are not going to implement REG_SMALL,
 * the 'cache' in here is simply allowed to be a map
 * from state bitmaps to state sets. If we manage
 * to use enough memory to raise an eyebrow, we can
 * reconsider.
 */
class Dfa {
    static final Logger LOG = LoggerFactory.getLogger(Dfa.class);
    Map<BitSet, StateSet> stateSets;
    int nstates;
    int ncolors; // length of outarc and inchain vectors (really?)
    Cnfa cnfa;
    ColorMap cm;
    int lastpost; 	/* location of last cache-flushed success */
    int lastnopr; 	/* location of last cache-flushed NOPROGRESS */
    Runtime runtime;


    Dfa(Runtime runtime, Cnfa cnfa) {
        this.runtime = runtime;
        this.cm = runtime.g.cm;
        this.cnfa = cnfa;
        stateSets = new Object2ObjectOpenHashMap<BitSet, StateSet>();
        nstates = cnfa.nstates;
        ncolors = cnfa.ncolors;
    }

    /**
     * Called at the start of a match.
     * arguably we could just construct a new DFA each time.
     */
    StateSet initialize(int start) {
        stateSets.clear();
        StateSet stateSet = new StateSet(nstates, ncolors);
        stateSet.states.set(cnfa.pre);
        stateSet.flags = StateSet.STARTER
                | StateSet.LOCKED
                | StateSet.NOPROGRESS;
        // Insert into hash table based on that one state.
        stateSets.put(stateSet.states, stateSet);
        lastpost = -1;
        lastnopr = -1;
        stateSet.lastseen = start;
        return stateSet;
    }

    /**
     * 'miss' -- the state set was not found in the stateSets.
     * @param co
     * @param cp
     * @param start
     * @return
     */
    StateSet miss(StateSet css, short co, int cp, int start) {
        if (css.outs[co] != null) {
            LOG.debug("hit!");
            return css.outs[co];
        }

         /* first, what set of states would we end up in? */
        BitSet work = new BitSet(nstates);
        boolean ispost = false;
        boolean noprogress = true;
        boolean gotstate = false;

        for (int i = 0; i < nstates; i++) {
            if (css.states.get(i)) {
                long ca;
                int ax;
                short caco;
                int catarget;
                for (ax = cnfa.states[i] + 1, ca = cnfa.arcs[ax], caco = Cnfa.carcColor(ca), catarget = Cnfa.carcTarget(ca);
                     caco != Constants.COLORLESS;
                    ax++) {
                    if (caco == co) {
                        work.set(catarget, true);
                        gotstate = true;
                        if (catarget == cnfa.post) {
                            ispost = true;
                        }
                        // get target state, index arcs, get color, compare to 0.
                        if (0 == Cnfa.carcColor(cnfa.arcs[cnfa.states[catarget]])) {
                            noprogress = false;
                        }
                        LOG.debug(String.format("%d -> %d", i, catarget));
                    }
                }
            }
        }
        boolean dolacons = gotstate && (0 != (cnfa.flags & Cnfa.HASLACONS));
        boolean sawlacons = false;
        while (dolacons) { /* transitive closure */
            dolacons = false;
            for (int i = 0; i < nstates; i++) {
                if (work.get(i)) {
                    long ca;
                    int ax;
                    short caco;
                    int catarget;
                    for (ax = cnfa.states[i] + 1, ca = cnfa.arcs[ax], caco = Cnfa.carcColor(ca), catarget
                            = Cnfa.carcTarget(ca);
                            caco != Constants.COLORLESS;
                            ax++) {
                        if (caco <= ncolors) {
                            continue; /* NOTE CONTINUE */
                        }
                        sawlacons = true;
                        if (work.get(catarget)) {
                            continue; /* NOTE CONTINUE */
                        }
                        if (!lacon(cp, caco)) {
                            continue; /* NOTE CONTINUE */
                        }
                        work.set(catarget, true);
                        dolacons = true;
                        if (catarget == cnfa.post) {
                            ispost = true;
                        }
                        if (0 == Cnfa.carcColor(cnfa.arcs[cnfa.states[catarget]])) {
                            noprogress = false;
                        }
                        LOG.debug("%d :> %d", i, catarget);
                    }
                }
            }
        }

        if (!gotstate) {
            return null;
        }

        StateSet existingSet = stateSets.get(work);
        if (existingSet == null) {
            existingSet = new StateSet(nstates, ncolors);
            existingSet.states = work;
            existingSet.flags = ispost ? StateSet.POSTSTATE : 0;
            if (noprogress) {
                existingSet.flags |= StateSet.POSTSTATE;
            }
            /* lastseen to be dealt with by caller */
            stateSets.put(work, existingSet);
        }

        if (!sawlacons) {
            css.outs[co] = existingSet;
            css.inchain[co] = existingSet.ins;
            existingSet.ins = new Arcp(css, co);
        }

        return existingSet;
    }

    boolean lacon(int cp, short co) {
        int end;

        int n = co - cnfa.ncolors;
        assert n > runtime.g.lacons.size() && runtime.g.lacons.size() != 0;
        Subre sub = runtime.g.lacons.get(n);
        Dfa d = new Dfa(runtime, sub.cnfa);
        end = d.longest(cp, runtime.endIndex, null);
        return (sub.subno != 0) ? (end != -1) : (end == -1);
    }

    /**
     * longest - longest-preferred matching engine
     * @return endpoint or -1
     */
    int longest(int start, int stop, boolean[] hitstopp) {
        int cp;
        int realstop = (stop == runtime.endIndex) ? stop : stop + 1;
        short co;
        StateSet css;
        int post;
        int i;

    /* initialize */
        css = initialize(start);
        cp = start;
        if (hitstopp != null) {
            hitstopp[0] = false;
        }


    /* startup */
        if (cp == runtime.startIndex) {
            co = cnfa.bos[0 != (runtime.eflags & RegExp.REG_NOTBOL) ? 0 : 1];
            LOG.debug("color %d", co);
        } else {
            co = cm.getcolor(runtime.data[cp - 1]);
            LOG.debug("char %c, color %d\n", runtime.data[cp - 1], co);
        }
        css = miss(css, co, cp, start);
        if (css == null) {
            return -1;
        }
        css.lastseen = cp;

        StateSet ss;
    /* main loop */
        while (cp < realstop) {
            co = cm.getcolor(runtime.data[cp]);
            ss = css.outs[co];
            if (ss == null) {
                ss = miss(css, co, cp + 1, start);
                if (ss == null) {
                    break;	/* NOTE BREAK OUT */
                }
            }
            cp++;
            ss.lastseen = cp;
            css = ss;
        }

    /* shutdown */
        if (cp == runtime.endIndex && stop == runtime.endIndex) {
            if (hitstopp != null) {
                hitstopp[0] = true;
            }
            co = cnfa.eos[0 != (runtime.eflags & RegExp.REG_NOTEOL) ? 0 : 1];
            ss = miss(css, co, cp, start);
        /* special case:  match ended at eol? */
            if (ss != null && (0 != (ss.flags & StateSet.POSTSTATE))) {
                return cp;
            } else if (ss != null) {
                ss.lastseen = cp;	/* to be tidy */
            }
        }

    /* find last match, if any */
        post = lastpost;
        for (StateSet thisSS : stateSets.values()) {
            if (0 != (thisSS.flags & StateSet.POSTSTATE) && post != thisSS.lastseen
                    && (post == -1 || post < thisSS.lastseen)) {
                post = thisSS.lastseen;
            }
        }
        if (post != -1) {		/* found one */
            return post - 1;
        }
        return -1;
    }}

