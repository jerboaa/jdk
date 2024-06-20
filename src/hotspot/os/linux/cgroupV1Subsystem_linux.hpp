/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef CGROUP_V1_SUBSYSTEM_LINUX_HPP
#define CGROUP_V1_SUBSYSTEM_LINUX_HPP

#include "runtime/os.hpp"
#include "memory/allocation.hpp"
#include "cgroupSubsystem_linux.hpp"
#include "cgroupUtil_linux.hpp"

// Cgroups version 1 specific implementation

class CgroupV1Controller: public CgroupController {
  private:
    /* mountinfo contents */
    char* _root;
    char* _mount_point;
    /* Constructed subsystem directory */
    char* _path;

  public:
    CgroupV1Controller(char *root, char *mountpoint) : _root(os::strdup(root)),
                                                       _mount_point(os::strdup(mountpoint)),
                                                       _path(nullptr) {
      _cgroup_path = nullptr;
    }
    // Shallow copy constructor
    CgroupV1Controller(const CgroupV1Controller& o) : _root(o._root),
                                                      _mount_point(o._mount_point),
                                                      _path(o._path) {
      _cgroup_path = o._cgroup_path;
    }
    ~CgroupV1Controller() {
      // At least one subsystem controller exists with paths to malloc'd path
      // names
    }

    void set_subsystem_path(char *cgroup_path);
    char *subsystem_path() { return _path; }
    bool needs_hierarchy_adjustment();
    char *mount_point() { return _mount_point; }
};

class CgroupV1MemoryController final : public CgroupMemoryController {

  private:
    CgroupV1Controller _reader;
    CgroupV1Controller* reader() { return &_reader; }
  public:
    void set_subsystem_path(char *cgroup_path) {
      reader()->set_subsystem_path(cgroup_path);
    }
    jlong read_memory_limit_in_bytes(julong upper_bound) override;
    jlong memory_usage_in_bytes() override;
    jlong memory_and_swap_limit_in_bytes(julong host_mem, julong host_swap) override;
    jlong memory_and_swap_usage_in_bytes(julong host_mem, julong host_swap) override;
    jlong memory_soft_limit_in_bytes(julong upper_bound) override;
    jlong memory_max_usage_in_bytes() override;
    jlong rss_usage_in_bytes() override;
    jlong cache_usage_in_bytes() override;
    jlong kernel_memory_usage_in_bytes();
    jlong kernel_memory_limit_in_bytes(julong host_mem);
    jlong kernel_memory_max_usage_in_bytes();
    void print_version_specific_info(outputStream* st, julong host_mem) override;
    bool needs_hierarchy_adjustment() override;
    CgroupV1MemoryController* adjust_controller(julong phys_mem) override;
  private:
    jlong read_mem_swappiness();
    jlong read_mem_swap(julong host_total_memsw);

  public:
    CgroupV1MemoryController(CgroupV1Controller reader)
      : _reader(reader) {
    }

};

class CgroupV1CpuController final : public CgroupCpuController {

  private:
    CgroupV1Controller _reader;
    CgroupV1Controller* reader() { return &_reader; }
  public:
    int cpu_quota() override;
    int cpu_period() override;
    int cpu_shares() override;
    void set_subsystem_path(char *cgroup_path) {
      reader()->set_subsystem_path(cgroup_path);
    }

  public:
    CgroupV1CpuController(CgroupV1Controller reader) : _reader(reader) {
    }
    bool needs_hierarchy_adjustment();
    CgroupV1CpuController* adjust_controller(int host_cpus);
};

class CgroupV1Subsystem: public CgroupSubsystem {

  public:
    jlong kernel_memory_usage_in_bytes();
    jlong kernel_memory_limit_in_bytes();
    jlong kernel_memory_max_usage_in_bytes();

    char * cpu_cpuset_cpus();
    char * cpu_cpuset_memory_nodes();

    jlong pids_max();
    jlong pids_current();

    void print_version_specific_info(outputStream* st);

    const char * container_type() {
      return "cgroupv1";
    }
    CachingCgroupController<CgroupMemoryController>* memory_controller() { return _memory; }
    CachingCgroupController<CgroupCpuController>* cpu_controller() { return _cpu; }

  private:
    /* controllers */
    CachingCgroupController<CgroupMemoryController>* _memory = nullptr;
    CgroupV1Controller* _cpuset = nullptr;
    CachingCgroupController<CgroupCpuController>* _cpu = nullptr;
    CgroupV1Controller* _cpuacct = nullptr;
    CgroupV1Controller* _pids = nullptr;

  public:
    CgroupV1Subsystem(CgroupV1Controller* cpuset,
                      CgroupV1CpuController* cpu,
                      CgroupV1Controller* cpuacct,
                      CgroupV1Controller* pids,
                      CgroupV1MemoryController* memory) :
      _memory(new CachingCgroupController<CgroupMemoryController>(
                                         CgroupUtil::adjust_controller(memory))),
      _cpuset(cpuset),
      _cpu(new CachingCgroupController<CgroupCpuController>(
                                         CgroupUtil::adjust_controller(cpu))),
      _cpuacct(cpuacct),
      _pids(pids) {
    }
};

#endif // CGROUP_V1_SUBSYSTEM_LINUX_HPP
