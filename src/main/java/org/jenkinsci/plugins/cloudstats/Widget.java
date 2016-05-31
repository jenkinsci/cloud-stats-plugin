/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.cloudstats;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import java.util.List;

/**
 * @author ogondza.
 */
@Restricted(DoNotUse.class) @Extension
public class Widget extends hudson.widgets.Widget {

//    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
//    public static void _register() {
//        List<hudson.widgets.Widget> widgets = Jenkins.getInstance().getWidgets();
//        for (hudson.widgets.Widget w: widgets) {
//            if (w instanceof Widget) return;
//        }
//        System.out.println(Jenkins.getInstance().getWidgets());
//        widgets.add(new Widget());
//        System.out.println(Jenkins.getInstance().getWidgets());
//    }
}
