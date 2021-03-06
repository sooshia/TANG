/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
﻿using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Com.Microsoft.Tang.Types;

namespace Com.Microsoft.Tang.Implementations
{
    public class Constructor : InjectionPlan
    {
        IConstructorDef constructor;
        InjectionPlan[] args;
        int numAlternatives;
        bool isAmbiguous;
        bool isInjectable;

        public InjectionPlan[] GetArgs() 
        {
            return args;
        }

        public ICollection<InjectionPlan> GetChildren() 
        {
            return new ReadOnlyCollection<InjectionPlan>(this.args.OfType<InjectionPlan>().ToList());
        }

        public IConstructorDef GetConstructorDef()
        {
            return constructor;
        }

        public Constructor(IClassNode classNode,
            IConstructorDef constructor, InjectionPlan[] args) : base(classNode)
        {
            this.constructor = constructor;
            this.args = args;
            int curAlternatives = 1;
            bool curAmbiguous = false;
            bool curInjectable = true;
            foreach (InjectionPlan plan in args) 
            {
                curAlternatives *= plan.GetNumAlternatives();
                curAmbiguous |= plan.IsAmbiguous();
                curInjectable &= plan.IsInjectable();
            }
            this.numAlternatives = curAlternatives;
            this.isAmbiguous = curAmbiguous;
            this.isInjectable = curInjectable;
        }

        public IClassNode GetNode()
        {
            return (IClassNode) node;
        }

        public override int GetNumAlternatives() 
        {
            return numAlternatives;
        }

        public override bool IsAmbiguous() 
        {
            return isAmbiguous;
        }

        public override bool IsInjectable() 
        {
            return isInjectable;
        }
        
        public override string ToString() 
        {
            StringBuilder sb = new StringBuilder("new " + GetNode().GetName() + '(');
            if (args.Length > 0) 
            {
                sb.Append(args[0]);
                for (int i = 1; i < args.Length; i++) 
                {
                    sb.Append(", " + args[i]);
                }
            }
            sb.Append(')');
            return sb.ToString();
        }

        private String ShallowArgString(InjectionPlan arg) 
        {
            if (arg is Constructor || arg is Subplan) 
            {
                return arg.GetType().Name + ": " + arg.GetNode().GetName();
            } 
            else 
            {
                return arg.ToShallowString();
            }
        }

        public override string ToShallowString() 
        {
            StringBuilder sb = new StringBuilder("new " + GetNode().GetName() + '(');
            if (args.Length > 0) 
            {
                sb.Append(ShallowArgString(args[0]));
                for (int i = 1; i < args.Length; i++) 
                {
                    sb.Append(", " + ShallowArgString(args[i]));
                }
            }
            sb.Append(')');
            return sb.ToString();
        }

        public override string ToAmbiguousInjectString() 
        {

            if (!isAmbiguous) 
            {
                throw new ArgumentException(GetNode().GetFullName() + " is NOT ambiguous.");
            }

            StringBuilder sb = new StringBuilder(GetNode().GetFullName() + " has ambiguous arguments: [ ");

            foreach (InjectionPlan plan in args) 
            {
                if (plan.IsAmbiguous()) 
                {
                    sb.Append(plan.ToAmbiguousInjectString());
                }
            }

            sb.Append(']');
            return sb.ToString();
        }

        public override string ToInfeasibleInjectString() 
        {
            IList<InjectionPlan> leaves = new List<InjectionPlan>();

            foreach (InjectionPlan ip in args) 
            {
                if (!ip.IsFeasible()) 
                {
                    if (ip.IsInfeasibleLeaf()) 
                    {
                        leaves.Add(ip);
                    } else 
                    {
                        return ip.ToInfeasibleInjectString();
                    }
                }
            }

            if (leaves.Count == 0) 
            {
                throw new ArgumentException(GetNode().GetFullName() + " has NO infeasible leaves.");
            }

            if (leaves.Count == 1) 
            {
                return GetNode().GetFullName() + " missing argument " + leaves[0].GetNode().GetFullName();
            } 
            else 
            {
                StringBuilder sb = new StringBuilder(GetNode().GetFullName() + " missing arguments: [ ");
                foreach (InjectionPlan leaf in leaves) 
                {
                    sb.Append(leaf.GetNode().GetFullName() + ' ');
                }
                sb.Append(']');
                return sb.ToString();
            }
        }

        public override bool IsInfeasibleLeaf() 
        {
            return false;
        }

        public override bool HasFutureDependency() 
        {
            foreach (InjectionPlan p in args) 
            {
                if(p.HasFutureDependency()) 
                {
                    return true;
                }
            }
            return false;
        }
    }
}
